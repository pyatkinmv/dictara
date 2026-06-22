package com.dictara.gateway.controller

import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.springframework.transaction.annotation.Transactional
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
class ExportController(
    private val submissionRepo: SubmissionRepository,
    private val audioMetaRepo: AudioMetaRepository,
    private val transcriptRepo: TranscriptRepository,
    private val diarizationRepo: DiarizationRepository,
    private val summaryRepo: SummaryRepository,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    @GetMapping("/export")
    @Transactional(readOnly = true)
    fun export(
        @RequestParam(defaultValue = "false") includeAudio: Boolean,
        servletRequest: HttpServletRequest,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

        val exportDate = dateFmt.format(java.time.Instant.now())
        response.contentType = "application/zip"
        response.setHeader("Content-Disposition", "attachment; filename=\"dictara_export_$exportDate.zip\"")

        val submissions = submissionRepo.findByUserIdAndStatusOrderByCreatedAtAsc(userId, "done")
        log.info("Export: userId={} submissions={} includeAudio={}", userId, submissions.size, includeAudio)
        val audioById = audioMetaRepo.findAllById(submissions.mapNotNull { it.audioId }).associateBy { it.id }

        // Collect all data within the transaction before streaming
        data class ExportItem(
            val folderName: String,
            val transcriptText: String?,
            val summaryText: String?,
            val originalName: String?,
            val storageUri: String?,
        )

        val usedFolderNames = mutableSetOf<String>()
        val items = submissions.map { submission ->
            val audio = audioById[submission.audioId]
            val datePrefix = dateFmt.format(submission.createdAt)
            val rawName = audio?.originalName ?: "audio"
            val baseName = rawName.substringBeforeLast('.', rawName)
            val sanitized = sanitize(baseName).take(100)
            val folderBase = "${datePrefix}_${sanitized}"
            var folderName = folderBase
            var suffix = 2
            while (!usedFolderNames.add(folderName)) {
                folderName = "${folderBase}_${suffix++}"
            }
            val id = submission.id!!
            ExportItem(
                folderName = folderName,
                transcriptText = diarizationRepo.findBySubmissionId(id)?.formattedText
                    ?: transcriptRepo.findBySubmissionId(id)?.formattedText,
                summaryText = summaryRepo.findBySubmissionId(id)?.text,
                originalName = audio?.originalName,
                storageUri = audio?.storageUri,
            )
        }

        return StreamingResponseBody { out ->
            ZipOutputStream(out).use { zip ->
                for (item in items) {
                    // transcript.txt
                    if (item.transcriptText != null) {
                        zip.putNextEntry(ZipEntry("${item.folderName}/transcript.txt"))
                        zip.write(item.transcriptText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    // summary.txt
                    if (!item.summaryText.isNullOrBlank()) {
                        zip.putNextEntry(ZipEntry("${item.folderName}/summary.txt"))
                        zip.write(item.summaryText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    // audio file (optional) — skip silently if unavailable (expired/not found)
                    if (includeAudio && item.storageUri != null && item.originalName != null) {
                        audioStorage.download(AudioRef(item.storageUri))?.use { audioStream ->
                            zip.putNextEntry(ZipEntry("${item.folderName}/${item.originalName}"))
                            audioStream.copyTo(zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("""[/\\:*?"<>|]"""), "_")
}
