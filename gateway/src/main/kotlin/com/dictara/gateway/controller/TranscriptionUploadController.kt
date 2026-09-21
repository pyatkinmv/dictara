package com.dictara.gateway.controller

import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.TagRef
import com.dictara.gateway.storage.AudioStorage
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Uploading a file for transcription from the web client (`POST /transcriptions`). The bot and the old client use `/transcribe`. */
@RestController
class TranscriptionUploadController(
    private val submissionService: SubmissionService,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** The two models the clients name; the transcriber knows them as `small` and `turbo`. */
        private val MODELS = mapOf("fast" to "small", "accurate" to "turbo")
        private val LANGUAGE = Regex("^(auto|[a-z]{2,3})$")
        const val MAX_SPEAKERS = 10
    }

    /**
     * What an upload answered: the entry as the list shows it, and whether it is one that already existed. A
     * file the user has uploaded before with the same options is not transcribed again (see
     * [SubmissionService.createSubmission]): the answer is the existing entry, whatever state it is in.
     */
    data class UploadResponse(val duplicate: Boolean, val item: TranscribeController.TranscriptionSummary<TagRef>)

    /**
     * Multipart body: `file`, and the options (all optional, as form fields or query parameters):
     *  - `language`: `auto` (default) or a language code such as `en`, `ru`;
     *  - `summary`: `off`, `auto` (default), `brief`, `concise` or `full`;
     *  - `diarize`: whether to tell the voices apart (default true); `num_speakers`: how many there are, 1–10,
     *    or left out to let the diarizer decide; ignored without `diarize`;
     *  - `model`: `accurate` (default) or `fast`.
     * A file the gateway does not take, or an option it does not know, is a 400 (413 for a file over 400 MB);
     * the monthly limit of the plan is a 429 with a message that can be shown as it is.
     */
    @PostMapping("/transcriptions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(defaultValue = "auto") language: String,
        @RequestParam(defaultValue = "auto") summary: String,
        @RequestParam(defaultValue = "true") diarize: Boolean,
        @RequestParam(name = "num_speakers", required = false) numSpeakers: Int?,
        @RequestParam(defaultValue = "accurate") model: String,
        servletRequest: HttpServletRequest,
    ): UploadResponse {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        TranscribeController.validateUpload(file)
        val languageHint = language.trim().lowercase().also {
            if (!LANGUAGE.matches(it)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "language is 'auto' or a language code such as 'en'")
        }
        val summaryMode = SummaryMode.entries.firstOrNull { it.name.equals(summary.trim(), ignoreCase = true) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "summary is one of ${SummaryMode.entries.map { it.name.lowercase() }}")
        val resolvedModel = MODELS[model.trim().lowercase()]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "model is one of ${MODELS.keys}")
        if (numSpeakers != null && numSpeakers !in 1..MAX_SPEAKERS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "num_speakers is between 1 and $MAX_SPEAKERS")
        }
        val speakers = if (diarize) numSpeakers else null // a count without diarization means nothing

        val originalName = file.originalFilename ?: "upload"
        val contentType = file.contentType ?: "application/octet-stream"
        val audioId = UUID.randomUUID()
        val uploadResult = audioStorage.upload(audioId, originalName, file.inputStream, file.size, contentType)

        val result = submissionService.createSubmission(
            userId = userId, audioId = audioId, originalName = originalName,
            contentType = contentType, sizeBytes = file.size, uploadResult = uploadResult,
            model = resolvedModel, languageHint = languageHint, diarize = diarize,
            numSpeakers = speakers, summaryMode = summaryMode.name.lowercase(), source = "web",
            telegramChatId = null, telegramMessageId = null,
        )
        if (result.dedup) {
            log.info("Duplicate upload: reusing submission=${result.submissionId}, file=$originalName")
        } else {
            log.info("Submission accepted: id=${result.submissionId}, file=$originalName, size=${file.size}B, model=$resolvedModel, language=$languageHint, diarize=$diarize, speakers=$speakers, summary=$summaryMode, user=$userId")
        }

        val item = submissionService.listItem(result.submissionId)
            ?.let { s ->
                TranscribeController.TranscriptionSummary(
                    s.jobId.toString(), s.fileName, s.title, s.createdAt, s.status, s.tags,
                    s.audioDurationS, s.speakerCount, s.processingPct, s.processingPhase,
                )
            }
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The upload was accepted but cannot be read back")
        return UploadResponse(result.dedup, item)
    }
}
