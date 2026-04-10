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
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = Executors.newCachedThreadPool()
    // Single-threaded: serializes all dispatch decisions so only one claim runs at a time
    private val dispatchExecutor = Executors.newSingleThreadExecutor()
    private val liveProgress = ConcurrentHashMap<UUID, ProgressInfo>()
    private val mapper = ObjectMapper().registerKotlinModule()

    /** Called by controller after creating a pending submission. */
    fun signalPending() {
        dispatchExecutor.submit { doDispatch() }
    }

    /** Returns live in-progress data for a running job (not persisted). */
    fun getLiveProgress(submissionId: UUID): ProgressInfo? = liveProgress[submissionId]

    /** On startup: resume any stage_attempts that were in-flight when the process crashed. */
    @PostConstruct
    fun resumeInFlight() {
        executor.submit {
            try {
                val inFlight = stateService.claimInFlightAttempts()
                if (inFlight.isEmpty()) {
                    log.info("Startup: no in-flight attempts to recover")
                } else {
                    log.info("Startup: found ${inFlight.size} in-flight attempt(s) to recover")
                }
                inFlight.forEach { attempt ->
                    if (attempt.externalJobId == null) {
                        // Gateway crashed after claiming but before submitting to transcriber — reset to queue
                        log.warn("Resetting submission ${attempt.submissionId} to pending (crashed before transcriber submit)")
                        stateService.resetToQueue(attempt)
                    } else {
                        log.info("Resuming transcription for submission ${attempt.submissionId} (externalJobId=${attempt.externalJobId})")
                        liveProgress[attempt.submissionId] = ProgressInfo("transcribing")
                        executor.submit { resumeTranscription(attempt) }
                    }
                }
                // After recovery, dispatch the next pending job if nothing is running
                dispatchNext()
            } catch (e: Exception) {
                log.error("Crash recovery error: ${e.message}", e)
            }
        }
    }

    /** Schedules a dispatch attempt on the single-threaded dispatchExecutor.
     *  Safe to call from any thread; returns immediately. */
    private fun dispatchNext() {
        dispatchExecutor.submit { doDispatch() }
    }

    /** Runs on dispatchExecutor only — serialized so no concurrent claims are possible. */
    private fun doDispatch() {
        val submission = stateService.claimNextPendingSubmission() ?: return
        log.info("Dispatching submission ${submission.id} (file=${submission.audio.originalName}, model=${submission.model}, language=${submission.language}, diarize=${submission.diarize}, summaryMode=${submission.summaryMode})")
        executor.submit { runTranscription(submission) }
    }

    // ── Transcription Stage ─────────────────────────────────────────────────

    private fun runTranscription(submission: SubmissionEntity) {
        val submissionId = submission.id!!
        val attempt = stateService.createAttempt(submissionId, "transcription")
        log.info("Transcription attempt ${attempt.attemptNum} started for submission $submissionId")

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
            log.info("Submission $submissionId submitted to transcriber (externalJobId=$transcriberJobId)")

            val snapshot = pollTranscriber(submissionId, transcriberJobId)
            val segmentsJson = mapper.writeValueAsString(snapshot.segments ?: emptyList<Any>())
            val formattedText = formatSegments(snapshot.segments ?: emptyList())

            stateService.saveTranscriptAndCompleteAttempt(
                submissionId, attempt.id, segmentsJson, formattedText, snapshot.audioDurationS,
            )
            liveProgress.remove(submissionId)
            log.info("Transcription complete for submission $submissionId (duration=${snapshot.audioDurationS?.let { "%.1fs".format(it) } ?: "unknown"}, segments=${snapshot.segments?.size ?: 0})")
            advanceToSummarization(submission, formattedText)

        } catch (e: Exception) {
            stateService.failAttempt(attempt.id!!, e.message)
            liveProgress.remove(submissionId)
            if (e is PermanentJobFailureException) {
                log.error("Transcription permanently failed for submission $submissionId: ${e.message}")
                stateService.failSubmission(submissionId)
                dispatchNext()
                return
            }
            val totalAttempts = stateService.countAttempts(submissionId, "transcription")
            if (totalAttempts < 3) {
                log.warn("Transcription attempt ${attempt.attemptNum} failed for submission $submissionId: ${e.message} — retrying ($totalAttempts/3)")
                // Retrying — job stays in 'processing', do NOT dispatch next
                Thread.sleep(props.transcriber.pollIntervalMs * 2)
                runTranscription(submission)
            } else {
                log.error("Transcription failed for submission $submissionId after $totalAttempts attempts: ${e.message}")
                stateService.failSubmission(submissionId)
                dispatchNext()
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
            log.info("Resumed transcription complete for submission $submissionId (duration=${snapshot.audioDurationS?.let { "%.1fs".format(it) } ?: "unknown"}, segments=${snapshot.segments?.size ?: 0})")

            val freshSubmission = stateService.loadSubmission(submissionId)
            advanceToSummarization(freshSubmission, formattedText)

        } catch (e: Exception) {
            stateService.failAttempt(attempt.id!!, e.message)
            liveProgress.remove(submissionId)
            val totalAttempts = stateService.countAttempts(submissionId, "transcription")
            if (totalAttempts >= 3) {
                log.error("Transcription failed for submission $submissionId after $totalAttempts attempts (resumed): ${e.message}")
                stateService.failSubmission(submissionId)
                dispatchNext()
            } else {
                log.warn("Resumed transcription failed for submission $submissionId ($totalAttempts/3 attempts): ${e.message}")
            }
            // If < 3, submission stays 'processing' and will be retried on next signalPending
        }
    }

    // ── Summarization Stage ─────────────────────────────────────────────────

    private fun advanceToSummarization(submission: SubmissionEntity, formattedText: String) {
        val summaryMode = SummaryMode.valueOf(submission.summaryMode.uppercase())
        if (summaryMode == SummaryMode.OFF) {
            log.info("Summarization skipped for submission ${submission.id} (mode=off)")
            stateService.completeSubmission(submission.id!!)
            dispatchNext()  // transcriber is now free
            return
        }
        if (!summarizer.isAvailable()) {
            log.warn("Summarization skipped for submission ${submission.id} (summarizer not available — check GEMINI_API_KEY)")
            stateService.completeSubmission(submission.id!!)
            dispatchNext()  // transcriber is now free
            return
        }
        log.info("Starting summarization for submission ${submission.id} (mode=$summaryMode, language=${submission.language})")
        stateService.startSummarizing(submission.id!!)
        dispatchNext()  // transcriber is now free — next job starts while summarization runs
        executor.submit { runSummarization(submission.id!!, submission.language, formattedText, summaryMode) }
    }

    private fun runSummarization(submissionId: UUID, language: String, formattedText: String, mode: SummaryMode) {
        val attempt = stateService.createAttempt(submissionId, "summarization")
        log.info("Summarization attempt ${attempt.attemptNum} started for submission $submissionId")

        try {
            val transcript = transcriptRepo.findBySubmissionId(submissionId)
            val summaryText = summarizer.summarize(
                text = formattedText,
                audioDurationSeconds = transcript?.audioDurationS,
                mode = mode,
                language = language,
            )
            stateService.saveSummaryAndCompleteAttempt(submissionId, attempt.id!!, summaryText)
            stateService.completeSubmission(submissionId)
            log.info("Summarization complete for submission $submissionId (${summaryText.length} chars)")

        } catch (e: Exception) {
            log.warn("Summarization attempt ${attempt.attemptNum} failed for submission $submissionId: ${e.message}")
            stateService.failAttempt(attempt.id!!, e.message)
            val totalAttempts = stateService.countAttempts(submissionId, "summarization")
            if (totalAttempts < 3) {
                Thread.sleep(2000)
                runSummarization(submissionId, language, formattedText, mode)
            } else {
                // Summarization failed — submission is still done (transcript is usable)
                log.error("Summarization permanently failed for submission $submissionId after $totalAttempts attempts — completing without summary")
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
                "failed" -> {
                    val msg = snapshot.error ?: "Transcriber job failed"
                    if (!snapshot.retryable) throw PermanentJobFailureException(msg)
                    throw RuntimeException(msg)
                }
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

/** Thrown when the transcriber reports a non-retryable failure (e.g. bad file format). */
class PermanentJobFailureException(message: String) : RuntimeException(message)
