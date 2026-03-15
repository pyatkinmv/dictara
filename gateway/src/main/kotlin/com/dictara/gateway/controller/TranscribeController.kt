package com.dictara.gateway.controller

import com.dictara.gateway.entity.*
import com.dictara.gateway.repository.*
import com.dictara.gateway.service.OrchestratorService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

@RestController
class TranscribeController(
    private val orchestrator: OrchestratorService,
    private val userRepo: UserRepository,
    private val authIdentityRepo: AuthIdentityRepository,
    private val audioMetaRepo: AudioMetaRepository,
    private val audioContentRepo: AudioContentRepository,
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val diarizationRepo: DiarizationRepository,
    private val summaryRepo: SummaryRepository,
    private val stageAttemptRepo: StageAttemptRepository,
) {
    private val mapper = ObjectMapper().registerKotlinModule()

    data class SubmitResponse(val jobId: String)

    data class ProgressResponse(
        val phase: String?,
        val processedS: Double?,
        val totalS: Double?,
        val diarizeProgress: Double?,
    )

    data class SegmentResponse(
        val start: Double,
        val end: Double,
        val text: String,
        val speaker: String?,
    )

    data class ResultResponse(
        val segments: List<SegmentResponse>?,
        val formattedText: String?,
        val audioDurationS: Double?,
        val summary: String?,
    )

    data class JobResponse(
        val jobId: String,
        val status: String,
        val progress: ProgressResponse?,
        val result: ResultResponse?,
        val durationS: Double?,
        val elapsedS: Double?,
        val error: String?,
    )

    @PostMapping("/transcribe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    fun submit(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(defaultValue = "fast") model: String,
        @RequestParam(defaultValue = "auto") language: String,
        @RequestParam(defaultValue = "false") diarize: Boolean,
        @RequestParam(name = "num_speakers", required = false) numSpeakers: Int?,
        @RequestParam(name = "summary_mode", defaultValue = "off") summaryMode: String,
        @RequestHeader(name = "X-Telegram-Chat-Id", required = false) telegramChatId: String?,
        @RequestHeader(name = "X-Telegram-Display-Name", required = false) displayName: String?,
    ): SubmitResponse {
        val user = resolveUser(telegramChatId, displayName)
        val audio = saveAudio(file, user)
        val submission = submissionRepo.save(SubmissionEntity(
            user = user, audio = audio, model = model, language = language,
            diarize = diarize, numSpeakers = numSpeakers, summaryMode = summaryMode,
        ))
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCommit() { orchestrator.signalPending() }
            }
        )
        return SubmitResponse(submission.id.toString())
    }

    @GetMapping("/jobs/{jobId}")
    fun getJob(@PathVariable jobId: String): JobResponse {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw NoSuchElementException("Invalid job ID: $jobId")
        }
        val submission = submissionRepo.findById(id).orElseThrow {
            NoSuchElementException("Job $jobId not found")
        }

        val transcript = transcriptRepo.findBySubmissionId(id)
        val diarization = diarizationRepo.findBySubmissionId(id)
        val summary = summaryRepo.findBySubmissionId(id)
        val liveProgress = orchestrator.getLiveProgress(id)

        val segmentsJson = diarization?.segments ?: transcript?.segments
        val formattedText = diarization?.formattedText ?: transcript?.formattedText

        val transcriptionAttempts = stageAttemptRepo
            .findBySubmissionIdAndStageOrderByAttemptNumDesc(id, "transcription")
        val latestFailedAttempt = transcriptionAttempts.firstOrNull { it.status == "failed" }

        val durationS = if (submission.status in listOf("done", "failed")) {
            (submission.updatedAt.toEpochMilli() - submission.createdAt.toEpochMilli()) / 1000.0
        } else null

        val latestAttempt = transcriptionAttempts.firstOrNull()

        val elapsedS = if (submission.status == "processing" && latestAttempt != null) {
            (Instant.now().toEpochMilli() - latestAttempt.startedAt!!.toEpochMilli()) / 1000.0
        } else null

        @Suppress("UNCHECKED_CAST")
        val segments = segmentsJson?.let {
            (mapper.readValue(it, List::class.java) as List<Map<String, Any?>>).map { seg ->
                SegmentResponse(
                    start = (seg["start"] as Number).toDouble(),
                    end = (seg["end"] as Number).toDouble(),
                    text = seg["text"] as String,
                    speaker = seg["speaker"] as String?,
                )
            }
        }

        return JobResponse(
            jobId = jobId,
            status = submission.status,
            progress = liveProgress?.let {
                ProgressResponse(it.phase, it.processedS, it.totalS, it.diarizeProgress)
            },
            result = if (transcript != null) ResultResponse(
                segments = segments,
                formattedText = formattedText,
                audioDurationS = transcript.audioDurationS,
                summary = summary?.text,
            ) else null,
            durationS = durationS,
            elapsedS = elapsedS,
            error = latestFailedAttempt?.error,
        )
    }

    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    private fun resolveUser(telegramChatId: String?, displayName: String?): UserEntity {
        val chatId = telegramChatId ?: "anonymous"
        val existing = authIdentityRepo.findByProviderAndProviderUid("telegram", chatId)
        if (existing != null) return existing.user
        val user = userRepo.save(UserEntity(displayName = displayName ?: chatId))
        authIdentityRepo.save(AuthIdentityEntity(user = user, provider = "telegram", providerUid = chatId))
        return user
    }

    private fun saveAudio(file: MultipartFile, user: UserEntity): AudioMetaEntity {
        val meta = audioMetaRepo.save(AudioMetaEntity(
            user = user,
            originalName = file.originalFilename ?: "upload",
            contentType = file.contentType ?: "application/octet-stream",
            sizeBytes = file.size,
        ))
        audioContentRepo.save(AudioContentEntity(audioId = meta.id!!, data = file.bytes))
        return meta
    }
}
