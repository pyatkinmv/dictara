package com.dictara.gateway.client

import com.dictara.gateway.config.DictaraProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.UUID

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
@ConditionalOnProperty("dictara.storage.gcs.bucket")
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
