package com.dictara.gateway.service

import com.dictara.gateway.client.TranscribeParams
import com.dictara.gateway.client.TranscriberClient
import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.entity.StageAttemptEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.repository.AudioContentRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@Service
class OrchestratorService(
    private val transcriberClient: TranscriberClient,
    private val summarizer: SummarizerPort,
    private val props: com.dictara.gateway.config.DictaraProperties,
    private val stateService: SubmissionStateService,
    private val audioContentRepo: AudioContentRepository,
    private val transcriptRepo: TranscriptRepository,
) {
    private val executor = Executors.newCachedThreadPool()
    private val liveProgress = ConcurrentHashMap<UUID, ProgressInfo>()
    private val mapper = ObjectMapper().registerKotlinModule()

    /** Called by controller after creating a pending submission. */
    fun signalPending() {
        executor.submit { processPending() }
    }

    /** Returns live in-progress data for a running job (not persisted). */
    fun getLiveProgress(submissionId: UUID): ProgressInfo? = liveProgress[submissionId]

    /** On startup: resume any stage_attempts that were in-flight when the process crashed. */
    @PostConstruct
    fun resumeInFlight() {
        executor.submit {
            try {
                val inFlight = stateService.claimInFlightAttempts()
                inFlight.forEach { attempt ->
                    executor.submit { resumeTranscription(attempt) }
                }
            } catch (e: Exception) {
                System.err.println("Crash recovery error: ${e.message}")
            }
        }
    }

    private fun processPending() {
        val submissions = stateService.claimPendingSubmissions()
        submissions.forEach { submission ->
            executor.submit { runTranscription(submission) }
        }
    }

    // ── Transcription Stage ─────────────────────────────────────────────────

    private fun runTranscription(submission: SubmissionEntity) {
        val submissionId = submission.id!!
        val attempt = stateService.createAttempt(submissionId, "transcription")

        try {
            val audioContent = audioContentRepo.findById(submission.audio.id!!).orElseThrow()
            val params = TranscribeParams(
                model = submission.model,
                language = submission.language,
                diarize = submission.diarize,
                numSpeakers = submission.numSpeakers,
                originalFileName = submission.audio.originalName,
            )

            val transcriberJobId = transcriberClient.submit(audioContent.data, submission.audio.originalName, params)
            stateService.setAttemptExternalJobId(attempt.id!!, transcriberJobId)

            val snapshot = pollTranscriber(submissionId, transcriberJobId)
            val segmentsJson = mapper.writeValueAsString(snapshot.segments ?: emptyList<Any>())
            val formattedText = formatSegments(snapshot.segments ?: emptyList())

            stateService.saveTranscriptAndCompleteAttempt(
                submissionId, attempt.id!!, segmentsJson, formattedText, snapshot.audioDurationS,
            )
            liveProgress.remove(submissionId)
            advanceToSummarization(submission, formattedText)

        } catch (e: Exception) {
            stateService.failAttempt(attempt.id!!, e.message)
            liveProgress.remove(submissionId)
            val totalAttempts = stateService.countAttempts(submissionId, "transcription")
            if (totalAttempts < 3) {
                Thread.sleep(props.transcriber.pollIntervalMs * 2)
                runTranscription(submission)
            } else {
                stateService.failSubmission(submissionId)
            }
        }
    }

    private fun resumeTranscription(attempt: StageAttemptEntity) {
        val submissionId = attempt.submissionId
        try {
            val snapshot = pollTranscriber(submissionId, attempt.externalJobId!!)
            val segmentsJson = mapper.writeValueAsString(snapshot.segments ?: emptyList<Any>())
            val formattedText = formatSegments(snapshot.segments ?: emptyList())

            stateService.saveTranscriptAndCompleteAttempt(
                submissionId, attempt.id!!, segmentsJson, formattedText, snapshot.audioDurationS,
            )
            liveProgress.remove(submissionId)

            val freshSubmission = stateService.loadSubmission(submissionId)
            advanceToSummarization(freshSubmission, formattedText)

        } catch (e: Exception) {
            stateService.failAttempt(attempt.id!!, e.message)
            liveProgress.remove(submissionId)
            val totalAttempts = stateService.countAttempts(submissionId, "transcription")
            if (totalAttempts >= 3) {
                stateService.failSubmission(submissionId)
            }
            // If < 3, submission stays status=processing; acceptable Plan 1 limitation
        }
    }

    // ── Summarization Stage ─────────────────────────────────────────────────

    private fun advanceToSummarization(submission: SubmissionEntity, formattedText: String) {
        val summaryMode = SummaryMode.valueOf(submission.summaryMode.uppercase())
        if (summaryMode == SummaryMode.OFF || !summarizer.isAvailable()) {
            stateService.completeSubmission(submission.id!!)
            return
        }
        runSummarization(submission.id!!, submission.language, formattedText)
    }

    private fun runSummarization(submissionId: UUID, language: String, formattedText: String) {
        val attempt = stateService.createAttempt(submissionId, "summarization")

        try {
            val transcript = transcriptRepo.findBySubmissionId(submissionId)
            val summaryText = summarizer.summarize(
                text = formattedText,
                audioDurationSeconds = transcript?.audioDurationS,
                mode = SummaryMode.AUTO,
                language = language,
            )
            stateService.saveSummaryAndCompleteAttempt(submissionId, attempt.id!!, summaryText)
            stateService.completeSubmission(submissionId)

        } catch (e: Exception) {
            stateService.failAttempt(attempt.id!!, e.message)
            val totalAttempts = stateService.countAttempts(submissionId, "summarization")
            if (totalAttempts < 3) {
                Thread.sleep(2000)
                runSummarization(submissionId, language, formattedText)
            } else {
                // Summarization failed — submission is still done (transcript is usable)
                stateService.completeSubmission(submissionId)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun pollTranscriber(submissionId: UUID, transcriberJobId: String): com.dictara.gateway.client.TranscriberJobSnapshot {
        val deadlineMs = System.currentTimeMillis() + props.transcriber.timeoutHours * 60 * 60 * 1000L
        while (System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(props.transcriber.pollIntervalMs)
            val snapshot = transcriberClient.getJob(transcriberJobId)
            when (snapshot.status) {
                "processing" -> snapshot.progress?.let { liveProgress[submissionId] = it }
                "done" -> return snapshot
                "failed" -> throw RuntimeException(snapshot.error ?: "Transcriber job failed")
            }
        }
        throw RuntimeException("Timeout: transcription did not complete within ${props.transcriber.timeoutHours} hours")
    }

    private fun formatTimestamp(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }

    private fun formatSegments(segments: List<com.dictara.gateway.model.Segment>): String =
        segments.joinToString("\n") { seg ->
            val ts = "[${formatTimestamp(seg.start)} --> ${formatTimestamp(seg.end)}]"
            if (seg.speaker != null) "$ts [${seg.speaker}] ${seg.text}" else "$ts ${seg.text}"
        }
}
