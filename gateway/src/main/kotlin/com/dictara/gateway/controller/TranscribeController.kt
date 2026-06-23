package com.dictara.gateway.controller

import com.dictara.gateway.entity.*
import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.service.OrchestratorService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.UserService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID

@RestController
class TranscribeController(
    private val submissionService: SubmissionService,
    private val orchestrator: OrchestratorService,
    private val userService: UserService,
    private val userRepo: UserRepository,
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
    private val stageAttemptRepo: StageAttemptRepository,
    private val tagRepo: SubmissionTagRepository,
    private val telegramDeliveryRepo: TelegramDeliveryRepository,
    private val audioStorage: AudioStorage,
) {
    companion object {
        private val log = LoggerFactory.getLogger(TranscribeController::class.java)
        val SUPPORTED_EXTENSIONS = setOf("mp3", "mp4", "m4a", "wav", "ogg", "oga", "opus", "flac", "webm", "mkv", "avi", "mov")

        private val IMAGE_SIGNATURES = listOf(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())          to "JPEG",
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)                      to "PNG",
            byteArrayOf(0x47, 0x49, 0x46)                                     to "GIF",
            byteArrayOf(0x42, 0x4D)                                           to "BMP",
            byteArrayOf(0x52, 0x49, 0x46, 0x46)                               to "WebP/RIFF",
        )

        fun detectImageFormat(header: ByteArray): String? {
            for ((signature, name) in IMAGE_SIGNATURES) {
                if (header.size >= signature.size && header.take(signature.size).toByteArray().contentEquals(signature)) {
                    if (name == "WebP/RIFF") {
                        if (header.size >= 12 &&
                            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
                            header[10] == 0x42.toByte() && header[11] == 0x50.toByte()) return "WebP"
                        continue
                    }
                    return name
                }
            }
            return null
        }
    }

    data class SubmitResponse(val jobId: String, val dedup: Boolean = false)

    data class ProgressResponse(val phase: String?, val processedS: Double?, val totalS: Double?, val diarizeProgress: Double?)

    data class SegmentResponse(val start: Double, val end: Double, val text: String, val speaker: String?)

    data class ResultResponse(val segments: List<SegmentResponse>?, val formattedText: String?, val audioDurationS: Double?, val summary: String?)

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
            log.warn("Rejected upload: unsupported extension '.$ext' (file=${file.originalFilename})")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsupported format: .$ext. Supported: ${SUPPORTED_EXTENSIONS.sorted().joinToString(", ")}")
        }
        val header = file.inputStream.readNBytes(16)
        val imageFormat = detectImageFormat(header)
        if (imageFormat != null) {
            log.warn("Rejected upload: file appears to be a $imageFormat image (file=${file.originalFilename})")
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

        val resolvedModel = mapOf("fast" to "small", "accurate" to "turbo")[model] ?: model
        val source = if (telegramUserId != null) "telegram" else "web"
        val originalName = file.originalFilename ?: "upload"
        val contentType = file.contentType ?: "application/octet-stream"

        val audioId = UUID.randomUUID()
        val uploadResult = audioStorage.upload(audioId, originalName, file.inputStream, file.size, contentType)

        val result = submissionService.createSubmission(
            userId = user.id!!, audioId = audioId, originalName = originalName,
            contentType = contentType, sizeBytes = file.size, uploadResult = uploadResult,
            model = resolvedModel, language = language, diarize = diarize,
            numSpeakers = numSpeakers, summaryMode = summaryMode, source = source,
            telegramChatId = telegramChatId, telegramMessageId = telegramMessageId,
        )

        if (!result.dedup)
            log.info("Submission accepted: id=${result.submissionId}, file=$originalName, size=${file.size}B, model=$resolvedModel, language=$language, diarize=$diarize, summaryMode=$summaryMode, source=$source, user=${user.id}")
        else
            log.info("Duplicate upload: reusing submission=${result.submissionId}, file=$originalName")

        return SubmitResponse(result.submissionId.toString(), result.dedup)
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
        val summary = summaryRepo.findBySubmissionId(id)
        val liveProgress = orchestrator.getLiveProgress(id)

        val segmentsNode = transcript?.segments
        val formattedText = transcript?.formattedText

        val transcriptionAttempts = stageAttemptRepo.findBySubmissionIdAndStageOrderByAttemptNumDesc(id, "transcription")
        val latestFailedAttempt = transcriptionAttempts.firstOrNull { it.status == "failed" }

        val durationS = if (submission.status in listOf("done", "failed")) {
            (submission.updatedAt.toEpochMilli() - submission.createdAt.toEpochMilli()) / 1000.0
        } else null

        val latestAttempt = transcriptionAttempts.firstOrNull()
        val elapsedS = if (submission.status == "processing" && latestAttempt != null) {
            (Instant.now().toEpochMilli() - latestAttempt.startedAt!!.toEpochMilli()) / 1000.0
        } else null

        val segments = segmentsNode?.map { seg ->
            SegmentResponse(
                start = seg["start"].asDouble(),
                end = seg["end"].asDouble(),
                text = seg["text"].asText(),
                speaker = seg["speaker"]?.takeIf { !it.isNull }?.asText(),
            )
        }

        val tags = tagRepo.findBySubmissionId(id).map { it.tag }.sorted()

        val queuePosition = if (submission.status == "pending") {
            submissionRepo.countPendingSubmissionsBefore(submission.createdAt).toInt() + 1
        } else null

        return JobResponse(
            jobId = jobId,
            status = submission.status,
            progress = liveProgress?.let { ProgressResponse(it.phase, it.processedS, it.totalS, it.diarizeProgress) },
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

    data class TranscriptionSummary(val jobId: String, val fileName: String, val createdAt: String, val status: String, val tags: List<String>)

    @GetMapping("/transcriptions")
    fun listTranscriptions(servletRequest: HttpServletRequest): List<TranscriptionSummary> {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return submissionService.listForUser(userId).map {
            TranscriptionSummary(it.jobId.toString(), it.fileName, it.createdAt, it.status, it.tags)
        }
    }

    @GetMapping("/transcript")
    fun downloadTranscript(@RequestParam jobId: UUID): ResponseEntity<ByteArray> {
        val text = transcriptRepo.findBySubmissionId(jobId)?.formattedText
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transcript_$jobId.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(text.toByteArray(Charsets.UTF_8))
    }

    private val tagRegex = Regex("^[\\w-]{1,64}$")

    @PostMapping("/jobs/{jobId}/tags")
    fun addTag(@PathVariable jobId: String, @RequestBody body: Map<String, String>, servletRequest: HttpServletRequest): Map<String, List<String>> {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID")
        }
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val tag = body["tag"]?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing 'tag'")
        if (!tagRegex.matches(tag)) throw ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Tag must be 1–64 word characters or hyphens")
        return mapOf("tags" to submissionService.addTag(id, userId, tag))
    }

    @DeleteMapping("/jobs/{jobId}/tags/{tag}")
    fun removeTag(@PathVariable jobId: String, @PathVariable tag: String, servletRequest: HttpServletRequest): Map<String, List<String>> {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID")
        }
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return mapOf("tags" to submissionService.removeTag(id, userId, tag))
    }

    @GetMapping("/formats")
    fun formats() = mapOf("extensions" to SUPPORTED_EXTENSIONS.sorted())

    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    // ── Telegram delivery tracking ────────────────────────────────────────────

    data class PendingDeliveryResponse(val jobId: String, val chatId: Long, val telegramMessageId: Long?, val status: String, val error: String?)

    @GetMapping("/telegram/pending-deliveries")
    fun pendingDeliveries(): List<PendingDeliveryResponse> =
        telegramDeliveryRepo.findPendingDeliveries().map { d ->
            val submission = submissionRepo.findById(d.jobId).orElseThrow()
            val error = if (submission.status == "failed")
                stageAttemptRepo.findBySubmissionIdAndStageOrderByAttemptNumDesc(d.jobId, "transcription")
                    .firstOrNull { it.status == "failed" }?.error?.take(150)
            else null
            PendingDeliveryResponse(d.jobId.toString(), d.chatId, d.telegramMessageId, submission.status, error)
        }

    @PostMapping("/telegram/deliveries/{jobId}/ack")
    fun ackDelivery(@PathVariable jobId: String): Map<String, Boolean> {
        val count = telegramDeliveryRepo.claimDelivery(UUID.fromString(jobId))
        return mapOf("claimed" to (count > 0))
    }

    @PostMapping("/telegram/deliveries/{jobId}/delivered")
    fun confirmDelivered(@PathVariable jobId: String) {
        telegramDeliveryRepo.confirmDelivered(UUID.fromString(jobId))
    }

    @PostMapping("/telegram/deliveries/{jobId}/failed")
    fun deliveryFailed(@PathVariable jobId: String, @RequestBody body: Map<String, Any?>) {
        val retryAfterS = (body["retry_after_s"] as? Number)?.toLong()
        submissionService.scheduleDeliveryFailure(UUID.fromString(jobId), retryAfterS)
    }
}
