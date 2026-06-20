package com.dictara.gateway.storage

import com.dictara.gateway.config.DictaraProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.channels.Channels
import java.util.UUID

/** Matches only when `dictara.storage.gcs.bucket` resolves to a non-blank value.
 *  A plain `@ConditionalOnProperty` would also match the empty string that
 *  `${GCS_UPLOADS_BUCKET:}` resolves to when the env var is unset (its default
 *  "not equal to false" check treats "" as present), creating this bean — and
 *  making it issue real GCS calls — even when no bucket is configured. */
internal class GcsBucketConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        !context.environment.getProperty("dictara.storage.gcs.bucket").isNullOrBlank()
}

@Deprecated("Used only by DatabaseAudioStorage which is itself deprecated — GCS is now always required")
internal class GcsBucketNotConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata) =
        context.environment.getProperty("dictara.storage.gcs.bucket").isNullOrBlank()
}

/** Uploads submitted audio files to a GCS bucket so they can be referenced by URI
 *  instead of streamed over HTTP (Cloud Run enforces a hard 32 MiB request body limit).
 *  Active only when `dictara.storage.gcs.bucket` is configured.
 *
 *  Uploaded objects are NOT deleted by the application — the bucket has a 90-day
 *  lifecycle rule that expires them automatically. */
@Component
@Conditional(GcsBucketConfiguredCondition::class)
class GcsAudioStorage(props: DictaraProperties) : AudioStorage {

    private val log = LoggerFactory.getLogger(javaClass)
    private val bucket = props.storage.gcs.bucket
    private val storage: Storage = StorageOptions.getDefaultInstance().service

    /** Uploads [inputStream] to `gs://{bucket}/audio/{audioMetaId}/{fileName}` and returns the gs:// URI.
     *  Uses a resumable streaming upload (no full-file direct-buffer allocation) — required
     *  to avoid OOM when many large files are uploaded concurrently. */
    override fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): AudioRef.Gcs {
        val objectName = "audio/$audioMetaId/$fileName"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(contentType).build()
        storage.createFrom(blobInfo, inputStream)
        val uri = "gs://$bucket/$objectName"
        log.info("Uploaded audio to {} ({} bytes)", uri, sizeBytes)
        return AudioRef.Gcs(uri)
    }

    /** Downloads the GCS object referenced by [ref]. Returns null if the object is unavailable
     *  (expired by lifecycle rule, missing, or access denied). Returns null for non-GCS refs. */
    override fun download(ref: AudioRef): InputStream? = when (ref) {
        is AudioRef.Gcs -> try {
            val path = ref.uri.removePrefix("gs://$bucket/")
            Channels.newInputStream(storage.reader(BlobId.of(bucket, path)))
        } catch (e: Exception) {
            log.warn("Audio download failed for {}: {}", ref.uri, e.message)
            null
        }
        is AudioRef.Db -> null
    }
}
