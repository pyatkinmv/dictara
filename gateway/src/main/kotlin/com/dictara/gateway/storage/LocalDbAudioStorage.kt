package com.dictara.gateway.storage

import com.dictara.gateway.guard.PaidOperation
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Audio storage for local development: the bytes live in the `local_audio_objects` table of the
 * development database, so no bucket or Google credentials are needed and resetting the database
 * resets the audio too. References look like `db://audio/<date>/<id>/<file name>`.
 *
 * The table comes from `db/local/R__local_audio.sql`, which only the `local` profile puts on Flyway's
 * path. Production uses [GcsAudioStorage].
 */
@Profile("local")
@Component
class LocalDbAudioStorage(private val jdbc: JdbcTemplate) : AudioStorage {

    private val log = LoggerFactory.getLogger(javaClass)

    @PaidOperation

    override fun upload(
        audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String,
    ): UploadResult {
        // Whole file in memory: fine for development, the size limit is 400 MB.
        val bytes = inputStream.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val uri = "db://audio/${LocalDate.now()}/$audioMetaId/$fileName"
        put(uri, bytes)
        log.info("Stored audio at {} ({} bytes, sha256={})", uri, bytes.size, hash)
        return UploadResult(AudioRef(uri), hash)
    }

    override fun download(ref: AudioRef): InputStream? {
        val data = jdbc.query("SELECT data FROM local_audio_objects WHERE uri = ?", { rs, _ -> rs.getBytes(1) }, ref.uri)
            .firstOrNull()
        return data?.let { ByteArrayInputStream(it) }
    }

    override fun listObjects(): List<StorageObject> =
        jdbc.query("SELECT uri, created_at FROM local_audio_objects WHERE uri LIKE 'db://audio/%' ORDER BY created_at") { rs, _ ->
            StorageObject(rs.getString(1), rs.getTimestamp(2).toInstant())
        }

    override fun delete(ref: AudioRef) {
        jdbc.update("DELETE FROM local_audio_objects WHERE uri = ?", ref.uri)
    }

    @PaidOperation

    override fun uploadBytes(objectName: String, bytes: ByteArray) = put("db://$objectName", bytes)

    override fun isGone(ref: AudioRef): Boolean =
        jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM local_audio_objects WHERE uri = ?)", Boolean::class.java, ref.uri) == true

    override fun openFrom(ref: AudioRef, offset: Long): InputStream? {
        val data = jdbc.query("SELECT data FROM local_audio_objects WHERE uri = ?", { rs, _ -> rs.getBytes(1) }, ref.uri)
            .firstOrNull() ?: return null
        val off = offset.toInt()
        return ByteArrayInputStream(data, off, data.size - off)
    }

    private fun put(uri: String, bytes: ByteArray) {
        jdbc.update(
            """INSERT INTO local_audio_objects (uri, data) VALUES (?, ?)
               ON CONFLICT (uri) DO UPDATE SET data = EXCLUDED.data""",
            uri, bytes,
        )
    }
}
