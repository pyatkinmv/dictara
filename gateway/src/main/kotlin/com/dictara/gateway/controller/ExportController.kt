package com.dictara.gateway.controller

import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
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
    private val transcriptRepo: TranscriptRepository,
    private val diarizationRepo: DiarizationRepository,
    private val summaryRepo: SummaryRepository,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    @GetMapping("/export")
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

        return StreamingResponseBody { out ->
            val submissions = submissionRepo.findByUser_IdAndStatusOrderByCreatedAtAsc(userId, "done")
            log.info("Export: userId={} submissions={} includeAudio={}", userId, submissions.size, includeAudio)

            val usedFolderNames = mutableSetOf<String>()

            ZipOutputStream(out).use { zip ->
                for (submission in submissions) {
                    val datePrefix = dateFmt.format(submission.createdAt)
                    val baseName = submission.audio.originalName
                        .substringBeforeLast('.', submission.audio.originalName)
                    val sanitized = sanitize(baseName).take(100)
                    val folderBase = "${datePrefix}_${sanitized}"
                    var folderName = folderBase
                    var suffix = 2
                    while (!usedFolderNames.add(folderName)) {
                        folderName = "${folderBase}_${suffix++}"
                    }

                    val id = submission.id!!

                    // transcript.txt
                    val transcriptText = diarizationRepo.findBySubmissionId(id)?.formattedText
                        ?: transcriptRepo.findBySubmissionId(id)?.formattedText
                    if (transcriptText != null) {
                        zip.putNextEntry(ZipEntry("$folderName/transcript.txt"))
                        zip.write(transcriptText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    // summary.txt
                    val summaryText = summaryRepo.findBySubmissionId(id)?.text
                    if (!summaryText.isNullOrBlank()) {
                        zip.putNextEntry(ZipEntry("$folderName/summary.txt"))
                        zip.write(summaryText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    // audio file (optional) — skip silently if unavailable (expired/not found)
                    if (includeAudio) {
                        val ref = AudioRef.from(submission.audio.id!!, submission.audio.storageUri)
                        audioStorage.download(ref)?.use { audioStream ->
                            zip.putNextEntry(ZipEntry("$folderName/${submission.audio.originalName}"))
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
