package com.dictara.gateway.controller

import com.dictara.gateway.service.SubmissionService
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
    private val submissionService: SubmissionService,
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

        val items = submissionService.loadExportData(userId)
        log.info("Export: userId={} submissions={} includeAudio={}", userId, items.size, includeAudio)

        val usedFolderNames = mutableSetOf<String>()
        val entries = items.map { item ->
            val datePrefix = dateFmt.format(item.createdAt)
            val rawName = item.originalName ?: "audio"
            val baseName = rawName.substringBeforeLast('.', rawName)
            val sanitized = sanitize(baseName).take(100)
            val folderBase = "${datePrefix}_${sanitized}"
            var folderName = folderBase
            var suffix = 2
            while (!usedFolderNames.add(folderName)) { folderName = "${folderBase}_${suffix++}" }
            folderName to item
        }

        return StreamingResponseBody { out ->
            ZipOutputStream(out).use { zip ->
                for ((folderName, item) in entries) {
                    if (item.transcriptText != null) {
                        zip.putNextEntry(ZipEntry("$folderName/transcript.txt"))
                        zip.write(item.transcriptText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    if (!item.summaryText.isNullOrBlank()) {
                        zip.putNextEntry(ZipEntry("$folderName/summary.txt"))
                        zip.write(item.summaryText.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    if (includeAudio && item.storageUri != null && item.originalName != null) {
                        audioStorage.download(AudioRef(item.storageUri))?.use { audioStream ->
                            zip.putNextEntry(ZipEntry("$folderName/${item.originalName}"))
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
