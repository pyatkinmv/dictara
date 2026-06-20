package com.dictara.gateway.controller

import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.repository.AudioContentRepository
import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.GcsAudioStorage
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/admin")
class AdminController(
    private val audioMetaRepo: AudioMetaRepository,
    private val audioContentRepo: AudioContentRepository,
    private val gcsStorage: GcsAudioStorage? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class MigrateRequest(val ids: List<String> = emptyList())
    data class MigrateResponse(val migrated: Int, val skipped: Int, val failed: Int, val errors: List<String>)

    /** Migrates audio BLOBs from audio_content to GCS.
     *  Pass specific IDs to migrate a subset; omit or send empty list to migrate all remaining.
     *
     *  @deprecated Migration completed 2026-06-21 — audio_content table is now empty. */
    @Deprecated("Migration completed 2026-06-21 — audio_content is empty, all files are in GCS")
    @PostMapping("/migrate-audio")
    fun migrateAudio(
        @RequestBody(required = false) body: MigrateRequest?,
        servletRequest: HttpServletRequest,
    ): MigrateResponse {
        val authenticatedUserId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")

        val storage = gcsStorage
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GCS not configured — set GCS_UPLOADS_BUCKET")

        val ids: List<UUID> = if (body?.ids.isNullOrEmpty()) {
            log.info("migrate-audio: no IDs specified, migrating all remaining BLOBs (requested by user={})", authenticatedUserId)
            audioContentRepo.findAllAudioIds()
        } else {
            body!!.ids.map { UUID.fromString(it) }
        }

        log.info("migrate-audio: starting migration of {} file(s)", ids.size)

        var migrated = 0
        var skipped = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (id in ids) {
            try {
                val meta = audioMetaRepo.findById(id).orElse(null)
                if (meta == null) {
                    log.warn("migrate-audio: audio_meta {} not found, skipping", id)
                    errors.add("$id: audio_meta not found")
                    failed++
                    continue
                }
                if (meta.storageUri != null) {
                    log.info("migrate-audio: {} already has storageUri={}, skipping", id, meta.storageUri)
                    skipped++
                    continue
                }
                val content = audioContentRepo.findById(id).orElse(null)
                if (content == null) {
                    log.warn("migrate-audio: audio_content {} not found, skipping", id)
                    errors.add("$id: audio_content not found")
                    failed++
                    continue
                }

                log.info("migrate-audio: uploading {} ({} bytes, {})", id, content.data.size, meta.originalName)
                val ref = storage.upload(id, meta.originalName, content.data.inputStream(), content.data.size.toLong(), meta.contentType)
                val uri = ref.storageUri!!

                audioMetaRepo.save(AudioMetaEntity(
                    id = meta.id, user = meta.user, originalName = meta.originalName,
                    contentType = meta.contentType, sizeBytes = meta.sizeBytes,
                    createdAt = meta.createdAt, storageUri = uri,
                ))
                log.info("migrate-audio: {} migrated to {}", id, uri)
                migrated++
            } catch (e: Exception) {
                log.error("migrate-audio: failed to migrate {}: {}", id, e.message, e)
                errors.add("$id: ${e.message}")
                failed++
            }
        }

        log.info("migrate-audio: done — migrated={}, skipped={}, failed={}", migrated, skipped, failed)
        return MigrateResponse(migrated, skipped, failed, errors)
    }
}
