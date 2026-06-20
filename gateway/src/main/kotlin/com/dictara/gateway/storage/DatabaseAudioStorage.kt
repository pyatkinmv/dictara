package com.dictara.gateway.storage

import com.dictara.gateway.entity.AudioContentEntity
import com.dictara.gateway.repository.AudioContentRepository
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import java.io.InputStream
import java.util.UUID

/** Stores audio as a BLOB in the `audio_content` PostgreSQL table.
 *  Active only when `dictara.storage.gcs.bucket` is NOT configured (local dev fallback).
 *
 *  @deprecated All BLOBs have been migrated to GCS. This fallback is no longer used in production.
 *  Use [GcsAudioStorage] instead. */
@Deprecated("All audio content has been migrated to GCS. DatabaseAudioStorage is a dead fallback.")
@Component
@Conditional(GcsBucketNotConfiguredCondition::class)
class DatabaseAudioStorage(private val repo: AudioContentRepository) : AudioStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): UploadResult {
        repo.save(AudioContentEntity(audioId = audioMetaId, data = inputStream.readBytes()))
        log.info("Audio {} stored as BLOB in database ({} bytes)", audioMetaId, sizeBytes)
        return UploadResult(AudioRef.Db(audioMetaId), "")
    }

    override fun download(ref: AudioRef): InputStream? = when (ref) {
        is AudioRef.Db  -> repo.findById(ref.audioMetaId).orElse(null)?.data?.inputStream()
        is AudioRef.Gcs -> null
    }
}
