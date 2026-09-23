package com.dictara.gateway.controller

import com.dictara.gateway.entity.*
import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.service.ListSort
import com.dictara.gateway.service.ListStatuses
import com.dictara.gateway.service.OrchestratorService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.TagRef
import com.dictara.gateway.service.TranscriptionListFilter
import com.dictara.gateway.service.UserService
import com.dictara.gateway.util.TranscriptFormatter
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
    private val audioMetaRepo: AudioMetaRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
    private val stageAttemptRepo: StageAttemptRepository,
    private val tagRepo: TagRepository,
    private val telegramDeliveryRepo: TelegramDeliveryRepository,
    private val segmentQueries: TranscriptSegmentQueries,
    private val audioStorage: AudioStorage,
) {
    companion object {
        private val log = LoggerFactory.getLogger(TranscribeController::class.java)
        val SUPPORTED_EXTENSIONS = setOf("mp3", "mp4", "m4a", "wav", "ogg", "oga", "opus", "flac", "webm", "mkv", "avi", "mov")
        const val MAX_UPLOAD_SIZE_BYTES = 400L * 1024 * 1024

        /** Page size of the paged list when the client does not say; the largest it may ask for. */
        const val DEFAULT_PAGE_SIZE = 30
        const val MAX_PAGE_SIZE = 500

        /** The length of `transcripts.title`. */
        const val MAX_TITLE_LENGTH = 200

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

        /**
         * Refuses a file the gateway will not take: too large, not one of [SUPPORTED_EXTENSIONS], or a picture
         * with an audio extension. Shared by every endpoint that takes an upload, so they cannot drift apart.
         */
        fun validateUpload(file: MultipartFile) {
            if (file.size > MAX_UPLOAD_SIZE_BYTES) {
                throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large. Maximum allowed size is 400 MB.")
            }
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
        }
    }

    data class SubmitResponse(val jobId: String, val dedup: Boolean = false)

    data class ProgressResponse(val phase: String?, val processedS: Double?, val totalS: Double?, val diarizeProgress: Double?)

    data class SegmentResponse(val start: Double, val end: Double, val text: String, val speaker: String?)

    data class ResultResponse(val segments: List<SegmentResponse>?, val formattedText: String?, val audioDurationS: Double?, val detectedLanguage: String?, val summary: String?)

    data class JobResponse(
        val jobId: String,
        val status: String,
        val title: String?,
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
        validateUpload(file)

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
            model = resolvedModel, languageHint = language, diarize = diarize,
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
        val audioMeta = audioMetaRepo.findById(submission.audioId).orElse(null)
        val summary = summaryRepo.findBySubmissionId(id)
        val liveProgress = orchestrator.getLiveProgress(id)

        val phrases = transcript?.id?.let { segmentQueries.findByTranscriptId(it) }
        val formattedText = TranscriptFormatter.format(phrases ?: emptyList())

        val transcriptionAttempts = stageAttemptRepo.findBySubmissionIdAndStageOrderByAttemptNumDesc(id, "transcription")
        val latestFailedAttempt = transcriptionAttempts.firstOrNull { it.status == "failed" }

        val durationS = if (submission.status in listOf("done", "failed")) {
            (submission.updatedAt.toEpochMilli() - submission.createdAt.toEpochMilli()) / 1000.0
        } else null

        val latestAttempt = transcriptionAttempts.firstOrNull()
        val elapsedS = if (submission.status == "processing" && latestAttempt != null) {
            (Instant.now().toEpochMilli() - latestAttempt.startedAt!!.toEpochMilli()) / 1000.0
        } else null

        val segments = phrases?.map { seg ->
            SegmentResponse(start = seg.start, end = seg.end, text = seg.text, speaker = seg.speaker)
        }

        val tags = transcript?.let { tagRepo.findByTranscriptId(it.id!!) }?.map { it.name } ?: emptyList()

        val queuePosition = if (submission.status == "pending") {
            submissionRepo.countPendingSubmissionsBefore(submission.createdAt).toInt() + 1
        } else null

        return JobResponse(
            jobId = jobId,
            status = submission.status,
            title = transcript?.title ?: audioMeta?.originalName,
            progress = liveProgress?.let { ProgressResponse(it.phase, it.processedS, it.totalS, it.diarizeProgress) },
            result = if (transcript != null) ResultResponse(
                segments = segments,
                formattedText = formattedText,
                audioDurationS = audioMeta?.durationS,
                detectedLanguage = transcript.detectedLanguage,
                summary = summary?.text,
            ) else null,
            durationS = durationS,
            elapsedS = elapsedS,
            error = latestFailedAttempt?.error?.take(150),
            tags = tags,
            queuePosition = queuePosition,
        )
    }

    /** One entry of the list. [T] is how a tag is shown: the plain array keeps names (the old client), the paged shape sends [TagRef]s with id and color. */
    data class TranscriptionSummary<T>(
        val jobId: String,
        val fileName: String,
        val title: String,
        val createdAt: String,
        val status: String,
        val tags: List<T>,
        /** Seconds of audio; absent until the transcriber has finished. */
        val audioDurationS: Double? = null,
        /** Distinct diarizer speakers in the transcript; absent while there is no transcript. */
        val speakerCount: Int? = null,
        /** How far transcribing is, in percent; only while transcribing. */
        val processingPct: Int? = null,
        /** `transcribing`, `diarizing` or `summarizing` while the job is worked on. */
        val processingPhase: String? = null,
    )

    /** The paged shape of the list: what `GET /transcriptions?page=&size=` returns. */
    data class TranscriptionPage(val items: List<TranscriptionSummary<TagRef>>, val page: Int, val size: Int, val hasNext: Boolean)

    /**
     * The user's transcriptions, newest first unless `sort` says otherwise.
     *
     * Without `page`/`size` this is the plain array the production web client has always received. With
     * either of them it is a [TranscriptionPage] (`page` counts from 0, `size` defaults to
     * [DEFAULT_PAGE_SIZE]); new clients use that and the old shape can go once the old client is retired.
     *
     * The list can be narrowed and ordered, in either shape:
     *  - `q`: text to find in the title shown in the list, ignoring case;
     *  - `status`: any of `pending`, `processing` (which includes summarizing), `done`, `failed`; repeat
     *    the parameter for several;
     *  - `tag`: any of these tag names; repeat the parameter for several (names may contain commas, so a
     *    comma is not a separator);
     *  - `sort`: `newest` (default), `oldest`, `longest` or `name`.
     * An unknown status or sort is a 400 rather than being ignored, so a typo does not quietly return the
     * wrong list.
     */
    @GetMapping("/transcriptions")
    fun listTranscriptions(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) sort: String?,
        servletRequest: HttpServletRequest,
    ): Any {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        fun <T> summary(it: SubmissionService.SubmissionSummary, tag: (TagRef) -> T) =
            TranscriptionSummary(
                it.jobId.toString(), it.fileName, it.title, it.createdAt, it.status, it.tags.map(tag),
                it.audioDurationS, it.speakerCount, it.processingPct, it.processingPhase,
            )

        // Read straight from the request: binding a List would split "a,b" into two values.
        val statuses = servletRequest.getParameterValues("status").orEmpty().onEach {
            if (it !in ListStatuses.stored) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown status '$it'; expected one of ${ListStatuses.stored.keys.sorted()}",
                )
            }
        }.toSet()
        val filter = TranscriptionListFilter(
            search = q,
            statuses = statuses,
            tags = servletRequest.getParameterValues("tag").orEmpty().filter { it.isNotEmpty() }.toSet(),
            sort = if (sort == null) ListSort.NEWEST else ListSort.parse(sort) ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown sort '$sort'; expected one of ${ListSort.entries.map { it.param }}",
            ),
        )

        if (page == null && size == null) return submissionService.listForUser(userId, filter).map { summary(it) { tag -> tag.label } }

        val pageNumber = page ?: 0
        val pageSize = size ?: DEFAULT_PAGE_SIZE
        if (pageNumber < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 or more")
        if (pageSize !in 1..MAX_PAGE_SIZE) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and $MAX_PAGE_SIZE")
        }
        val result = submissionService.listPageForUser(userId, filter, pageNumber, pageSize)
        return TranscriptionPage(result.items.map { summary(it) { tag -> tag } }, pageNumber, pageSize, result.hasNext)
    }

    /**
     * One transcription with everything the detail page shows: file facts, tags, the summary (Markdown or
     * null) and the segments. 404 when it does not exist or belongs to someone else, so ids of other users
     * cannot be probed. `GET /jobs/{id}` stays as it is for the old client.
     */
    @GetMapping("/transcriptions/{id}")
    fun getTranscription(@PathVariable id: String, servletRequest: HttpServletRequest): SubmissionService.TranscriptionDetail {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        return submissionService.getDetail(userId, submissionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
    }

    @GetMapping("/transcript")
    fun downloadTranscript(@RequestParam jobId: UUID): ResponseEntity<ByteArray> {
        val text = transcriptRepo.findBySubmissionId(jobId)?.id
            ?.let { submissionService.resolvedTranscriptText(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transcript_$jobId.txt\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(text.toByteArray(Charsets.UTF_8))
    }

    private val tagRegex = Regex("^[\\w-]{1,32}$")

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
            "Tag must be 1–32 word characters or hyphens")
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

    @PatchMapping("/jobs/{jobId}/title")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun updateTitle(@PathVariable jobId: String, @RequestBody body: Map<String, String?>, servletRequest: HttpServletRequest) {
        val id = runCatching { UUID.fromString(jobId) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job ID")
        }
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val title = body["title"]?.trim()
        // The column is VARCHAR(200): say so instead of failing in the database.
        if (title != null && title.length > MAX_TITLE_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must be at most $MAX_TITLE_LENGTH characters")
        }
        submissionService.updateTitle(id, userId, title)
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
