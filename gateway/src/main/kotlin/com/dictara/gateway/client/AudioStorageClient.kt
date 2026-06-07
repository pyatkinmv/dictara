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

    /** Uploads [bytes] to `gs://{bucket}/audio/{objectKey}/{fileName}` and returns the gs:// URI.
     *  [objectKey] only namespaces the object path — it need not match any DB id. */
    fun upload(objectKey: UUID, fileName: String, bytes: ByteArray, contentType: String): String {
        val objectName = "audio/$objectKey/$fileName"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(contentType).build()
        storage.create(blobInfo, bytes)
        val uri = "gs://$bucket/$objectName"
        log.info("Uploaded audio to {} ({} bytes)", uri, bytes.size)
        return uri
    }
}
