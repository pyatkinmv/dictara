package com.dictara.gateway.client

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

/** Uploads submitted audio files to a GCS bucket so they can be referenced by URI
 *  instead of streamed over HTTP (Cloud Run enforces a hard 32 MiB request body
 *  limit — see docs/cloud-run-migration.md). Active only when
 *  `dictara.storage.gcs.bucket` is configured; absent otherwise (local/dev fallback
 *  to in-DB BLOB storage, see [com.dictara.gateway.entity.AudioMetaEntity.storageUri]).
 *
 *  Uploaded objects are NOT deleted by the application — the bucket has a 90-day
 *  lifecycle rule that expires them automatically (see docs/cloud-run-migration.md,
 *  infra setup). This keeps the gateway free of cleanup bookkeeping/retry logic. */
@Component
@Conditional(GcsBucketConfiguredCondition::class)
class AudioStorageClient(props: DictaraProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val bucket = props.storage.gcs.bucket
    private val storage: Storage = StorageOptions.getDefaultInstance().service

    /** Uploads [inputStream] to `gs://{bucket}/audio/{objectKey}/{fileName}` and returns the gs:// URI.
     *  Uses a resumable streaming upload (no full-file direct-buffer allocation) — required
     *  to avoid OOM when many large files are uploaded concurrently.
     *  [objectKey] only namespaces the object path — it need not match any DB id. */
    fun upload(objectKey: UUID, fileName: String, inputStream: java.io.InputStream, sizeBytes: Long, contentType: String): String {
        val objectName = "audio/$objectKey/$fileName"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(contentType).build()
        storage.createFrom(blobInfo, inputStream)
        val uri = "gs://$bucket/$objectName"
        log.info("Uploaded audio to {} ({} bytes)", uri, sizeBytes)
        return uri
    }

    /** Downloads the object at [storageUri] and returns a readable [InputStream], or `null` if
     *  the object is unavailable (expired by lifecycle rule, missing, or access denied). */
    fun download(storageUri: String): InputStream? = try {
        val path = storageUri.removePrefix("gs://$bucket/")
        Channels.newInputStream(storage.reader(BlobId.of(bucket, path)))
    } catch (e: Exception) {
        log.warn("Audio download failed for {}: {}", storageUri, e.message)
        null
    }
}
