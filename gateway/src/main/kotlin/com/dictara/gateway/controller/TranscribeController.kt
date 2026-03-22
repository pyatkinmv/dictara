package com.dictara.gateway.controller

import com.dictara.gateway.entity.*
import com.dictara.gateway.repository.*
import com.dictara.gateway.service.OrchestratorService
import com.dictara.gateway.service.UserService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

@RestController
class TranscribeController(
    private val orchestrator: OrchestratorService,
    private val userService: UserService,
    private val userRepo: UserRepository,
    private val audioMetaRepo: AudioMetaRepository,
    private val audioContentRepo: AudioContentRepository,
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val diarizationRepo: DiarizationRepository,
    private val summaryRepo: SummaryRepository,
    private val stageAttemptRepo: StageAttemptRepository,
    private val tagRepo: SubmissionTagRepository,
    private val telegramDeliveryRepo: TelegramDeliveryRepository,
) {
    companion object {
        val SUPPORTED_EXTENSIONS = setOf("mp3", "mp4", "m4a", "wav", "ogg", "oga", "opus", "flac", "webm", "mkv", "avi", "mov")

        // Magic byte signatures for common image formats that must be rejected
        private val IMAGE_SIGNATURES = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())          to "JPEG",
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)                      to "PNG",
            byteArrayOf(0x47, 0x49, 0x46)                                     to "GIF",
            byteArrayOf(0x42, 0x4D)                                           to "BMP",
            byteArrayOf(0x52, 0x49, 0x46, 0x46)                               to "WebP/RIFF",  // check WEBP at offset 8 below
        )

        /** Returns a human-readable image type name if the bytes match a known image signature, null otherwise. */
        fun detectImageFormat(header: ByteArray): String? {
            for ((signature, name) in IMAGE_SIGNATURES) {
                if (header.size >= signature.size && header.take(signature.size).toByteArray().contentEquals(signature)) {
                    // Extra check: RIFF files are WebP only when bytes 8–11 are "WEBP"
                    if (name == "WebP/RIFF") {
                        if (header.size >= 12 &&
                            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                            header[10] == 0x42.toByte() && header[11] == 0x50.toByte()) return "WebP"
                        continue  // regular RIFF (WAV, AVI) — not an image
                    }
                    return name
                }
            }
            return null
        }
    }

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
        val tags: List<String>,
        val queuePosition: Int?,
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
        @RequestHeader(name = "X-Telegram-User-Id", required = false) telegramUserId: String?,
        @RequestHeader(name = "X-Telegram-Username", required = false) telegramUsername: String?,
        @RequestHeader(name = "X-Telegram-First-Name", required = false) telegramFirstName: String?,
        @RequestHeader(name = "X-Telegram-Last-Name", required = false) telegramLastName: String?,
        @RequestHeader(name = "X-Telegram-Chat-Id", required = false) telegramChatId: Long?,
        @RequestHeader(name = "X-Telegram-Message-Id", required = false) telegramMessageId: Long?,
        servletRequest: HttpServletRequest,
    ): SubmitResponse {
        val ext = file.originalFilename?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (ext !in SUPPORTED_EXTENSIONS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported format: .$ext. Supported: ${SUPPORTED_EXTENSIONS.sorted().joinToString(", ")}")
        }
        val header = file.inputStream.readNBytes(16)
        val imageFormat = detectImageFormat(header)
        if (imageFormat != null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "File appears to be a $imageFormat image, not an audio/video file.")
        }
        val authenticatedUserId = servletRequest.getAttribute("authenticatedUserId") as UUID?
        fun dec(v: String?) = v?.let { URLDecoder.decode(it, "UTF-8") }
        val user = when {
            authenticatedUserId != null -> userRepo.findById(authenticatedUserId).orElseThrow()
            telegramUserId != null -> userService.resolveByTelegramId(telegramUserId, dec(telegramUsername), dec(telegramFirstName), dec(telegramLastName))
            else -> userService.resolveAnonymous()
        }
        val audio = saveAudio(file, user)
        val resolvedModel = mapOf("fast" to "small", "accurate" to "large-v3")[model] ?: model
        val source = if (telegramUserId != null) "telegram" else "web"
        val submission = submissionRepo.save(SubmissionEntity(
            user = user, audio = audio, model = resolvedModel, language = language,
            diarize = diarize, numSpeakers = numSpeakers, summaryMode = summaryMode, source = source,
        ))
        if (telegramChatId != null) {
            telegramDeliveryRepo.save(TelegramDeliveryEntity(jobId = submission.id!!, chatId = telegramChatId, telegramMessageId = telegramMessageId))
        }
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

        val tags = tagRepo.findBySubmissionId(id).map { it.tag }.sorted()

        // 'pending' = waiting in queue; 'processing' = transcriber is actively working (no position).
        val queuePosition = if (submission.status == "pending") {
            submissionRepo.countPendingSubmissionsBefore(submission.createdAt).toInt() + 1
        } else null

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
            error = latestFailedAttempt?.error?.take(150),
            tags = tags,
            queuePosition = queuePosition,
        )
    }

    data class TranscriptionSummary(
        val jobId: String,
        val fileName: String,
        val createdAt: String,
        val status: String,
        val tags: List<String>,
    )

    @GetMapping("/transcriptions")
    fun listTranscriptions(servletRequest: HttpServletRequest): List<TranscriptionSummary> {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val submissions = submissionRepo.findByUser_IdOrderByCreatedAtDesc(userId)
        val tagsBySubmission = tagRepo.findBySubmissionIdIn(submissions.mapNotNull { it.id })
            .groupBy({ it.submissionId }, { it.tag })
        return submissions.map {
            TranscriptionSummary(
                jobId = it.id.toString(),
                fileName = it.audio.originalName,
                createdAt = it.createdAt.toString(),
                status = it.status,
                tags = (tagsBySubmission[it.id] ?: emptyList()).sorted(),
            )
        }
    }

    private val tagRegex = Regex("^[\\w-]{1,64}$")

    @PostMapping("/jobs/{jobId}/tags")
    @Transactional
    fun addTag(
        @PathVariable jobId: String,
        @RequestBody body: Map<String, String>,
        servletRequest: HttpServletRequest,
    ): Map<String, List<String>> {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID")
        }
        val submission = submissionRepo.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found")
        }
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (submission.user.id != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        val tag = body["tag"]?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'tag'")
        if (!tagRegex.matches(tag)) throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Tag must be 1–64 word characters or hyphens")

        if (!tagRepo.existsBySubmissionIdAndTag(id, tag)) {
            tagRepo.save(SubmissionTagEntity(submissionId = id, tag = tag))
        }
        return mapOf("tags" to tagRepo.findBySubmissionId(id).map { it.tag }.sorted())
    }

    @DeleteMapping("/jobs/{jobId}/tags/{tag}")
    @Transactional
    fun removeTag(
        @PathVariable jobId: String,
        @PathVariable tag: String,
        servletRequest: HttpServletRequest,
    ): Map<String, List<String>> {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID")
        }
        val submission = submissionRepo.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found")
        }
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        if (submission.user.id != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)

        tagRepo.deleteBySubmissionIdAndTag(id, tag)
        return mapOf("tags" to tagRepo.findBySubmissionId(id).map { it.tag }.sorted())
    }

    @GetMapping("/formats")
    fun formats() = mapOf("extensions" to SUPPORTED_EXTENSIONS.sorted())

    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    // ── Telegram delivery tracking ────────────────────────────────────────────

    data class PendingDeliveryResponse(
        val jobId: String,
        val chatId: Long,
        val telegramMessageId: Long?,
        val status: String,
        val error: String?,
    )

    @GetMapping("/telegram/pending-deliveries")
    fun pendingDeliveries(): List<PendingDeliveryResponse> =
        telegramDeliveryRepo.findPendingDeliveries().map { d ->
            val submission = submissionRepo.findById(d.jobId).orElseThrow()
            val error = if (submission.status == "failed")
                stageAttemptRepo
                    .findBySubmissionIdAndStageOrderByAttemptNumDesc(d.jobId, "transcription")
                    .firstOrNull { it.status == "failed" }?.error?.take(150)
            else null
            PendingDeliveryResponse(d.jobId.toString(), d.chatId, d.telegramMessageId, submission.status, error)
        }

    @PostMapping("/telegram/deliveries/{jobId}/ack")
    @Transactional
    fun ackDelivery(@PathVariable jobId: String): Map<String, Boolean> {
        val count = telegramDeliveryRepo.claimDelivery(UUID.fromString(jobId))
        return mapOf("claimed" to (count > 0))
    }

    @PostMapping("/telegram/deliveries/{jobId}/delivered")
    @Transactional
    fun confirmDelivered(@PathVariable jobId: String) {
        telegramDeliveryRepo.confirmDelivered(UUID.fromString(jobId))
    }

    @PostMapping("/telegram/deliveries/{jobId}/failed")
    @Transactional
    fun deliveryFailed(@PathVariable jobId: String, @RequestBody body: Map<String, Any?>) {
        val id = UUID.fromString(jobId)
        val retryAfterS = (body["retry_after_s"] as? Number)?.toLong()
        val retryAfterTs = if (retryAfterS != null) {
            Instant.now().plusSeconds(retryAfterS)
        } else {
            val attempt = telegramDeliveryRepo.findById(id).map { it.attemptCount }.orElse(1)
            Instant.now().plusSeconds(minOf(30L shl (attempt - 1), 3600L))
        }
        telegramDeliveryRepo.scheduleRetry(id, retryAfterTs)
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
