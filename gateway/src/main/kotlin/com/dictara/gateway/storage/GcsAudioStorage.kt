package com.dictara.gateway.storage

import com.dictara.gateway.config.DictaraProperties
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.channels.Channels
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/** Uploads submitted audio files to a GCS bucket so they can be referenced by URI
 *  instead of streamed over HTTP (Cloud Run enforces a hard 32 MiB request body limit).
 *
 *  Uploaded objects are NOT deleted by the application — the bucket has a 90-day
 *  lifecycle rule that expires them automatically. */
@Component
class GcsAudioStorage(props: DictaraProperties) : AudioStorage {

    private val log = LoggerFactory.getLogger(javaClass)
    private val bucket = props.storage.gcs.bucket
    private val storage: Storage = StorageOptions.getDefaultInstance().service

    /** Uploads [inputStream] to `gs://{bucket}/audio/{audioMetaId}/{fileName}`.
     *  Computes SHA-256 hash of the content in a single streaming pass alongside the upload
     *  (no buffering in memory). Returns [UploadResult] with the gs:// URI and hex hash. */
    override fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): UploadResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val digestStream = DigestInputStream(inputStream, digest)
        val objectName = "audio/$audioMetaId/$fileName"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).setContentType(contentType).build()
        storage.createFrom(blobInfo, digestStream)
        val uri = "gs://$bucket/$objectName"
        val contentHash = digest.digest().joinToString("") { "%02x".format(it) }
        log.info("Uploaded audio to {} ({} bytes, sha256={})", uri, sizeBytes, contentHash)
        return UploadResult(AudioRef(uri), contentHash)
    }

    /** Downloads the GCS object referenced by [ref]. Returns null if the object is unavailable
     *  (expired by lifecycle rule, missing, or access denied). */
    override fun download(ref: AudioRef): InputStream? = try {
        val path = ref.uri.removePrefix("gs://$bucket/")
        Channels.newInputStream(storage.reader(BlobId.of(bucket, path)))
    } catch (e: Exception) {
        log.warn("Audio download failed for {}: {}", ref.uri, e.message)
        null
    }
}
