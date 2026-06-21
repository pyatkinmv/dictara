package com.dictara.gateway.controller

import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/admin")
class AdminController(
    private val audioStorage: AudioStorage,
    private val audioMetaRepo: AudioMetaRepository,
    @Value("\${jwt.secret}") private val jwtSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Lists audio_meta IDs that still need a content_hash backfilled. */
    @GetMapping("/backfill-hashes")
    fun listPending(@RequestHeader("Authorization") auth: String?): ResponseEntity<Map<String, Any>> {
        if (auth != "Bearer $jwtSecret") return ResponseEntity.status(403).build()
        val rows = audioMetaRepo.findAllByContentHashIsNullAndStorageUriIsNotNull()
        return ResponseEntity.ok(mapOf("count" to rows.size, "ids" to rows.map { it.id }))
    }

    /** Backfills content_hash for the given audio_meta IDs by streaming each file from GCS. */
    @PostMapping("/backfill-hashes")
    fun backfillHashes(
        @RequestHeader("Authorization") auth: String?,
        @RequestParam ids: List<UUID>,
    ): ResponseEntity<Map<String, Int>> {
        if (auth != "Bearer $jwtSecret") return ResponseEntity.status(403).build()

        var updated = 0; var skipped = 0; var failed = 0
        for (id in ids) {
            try {
                val meta = audioMetaRepo.findById(id).orElse(null)
                if (meta == null) { log.warn("Backfill: audio_meta {} not found", id); skipped++; continue }
                if (meta.contentHash != null) { skipped++; continue }
                val stream = audioStorage.download(AudioRef(meta.storageUri!!))
                if (stream == null) { log.warn("Backfill: GCS file not found for {}", id); skipped++; continue }
                val hash = stream.use { computeHash(it) }
                audioMetaRepo.updateContentHash(id, hash)
                log.info("Backfilled hash for {} → {}", id, hash)
                updated++
            } catch (e: Exception) {
                log.error("Backfill failed for {}: {}", id, e.message)
                failed++
            }
        }
        return ResponseEntity.ok(mapOf("processed" to ids.size, "updated" to updated, "skipped" to skipped, "failed" to failed))
    }

    private fun computeHash(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(stream, digest).use { dis -> val buf = ByteArray(8192); while (dis.read(buf) != -1) {} }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
