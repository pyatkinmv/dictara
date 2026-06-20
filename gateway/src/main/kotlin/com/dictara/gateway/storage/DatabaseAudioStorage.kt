package com.dictara.gateway.storage

import com.dictara.gateway.entity.AudioContentEntity
import com.dictara.gateway.repository.AudioContentRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.UUID

/** Stores audio as a BLOB in the `audio_content` PostgreSQL table.
 *  Active only when `dictara.storage.gcs.bucket` is NOT configured (local dev fallback). */
@Component
@Conditional(GcsBucketNotConfiguredCondition::class)
class DatabaseAudioStorage(private val repo: AudioContentRepository) : AudioStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Saves audio bytes to `audio_content` table. Returns null — no external URI. */
    override fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): String? {
        repo.save(AudioContentEntity(audioId = audioMetaId, data = inputStream.readBytes()))
        log.info("Audio {} stored as BLOB in database ({} bytes)", audioMetaId, sizeBytes)
        return null
    }

    override fun download(audioMetaId: UUID, storageUri: String?): InputStream? =
        repo.findById(audioMetaId).orElse(null)?.data?.inputStream()
}
