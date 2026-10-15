package com.dictara.gateway.storage

import com.dictara.gateway.SharedTestInfrastructure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.io.ByteArrayInputStream
import java.sql.DriverManager
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

class LocalDbAudioStorageTest {

    companion object {
        private lateinit var jdbc: JdbcTemplate
        private val scratchDb = "local_audio_" + UUID.randomUUID().toString().replace("-", "")

        private fun adminConnection() = DriverManager.getConnection(
            SharedTestInfrastructure.jdbcUrl, SharedTestInfrastructure.dbUsername, SharedTestInfrastructure.dbPassword,
        )

        /**
         * A database of its own: this table is not Flyway's, and in the shared database it made the Spring contexts that
         * start after this class fail with "non-empty schema but no schema history table".
         */
        @BeforeAll
        @JvmStatic
        fun createTable() {
            adminConnection().use { it.createStatement().execute("CREATE DATABASE $scratchDb") }
            jdbc = JdbcTemplate(
                DriverManagerDataSource(
                    SharedTestInfrastructure.jdbcUrl.replace(Regex("(jdbc:postgresql://[^/]+/)([^?]+)"), "$1$scratchDb"),
                    SharedTestInfrastructure.dbUsername,
                    SharedTestInfrastructure.dbPassword,
                )
            )
            // The same script Flyway runs under the local profile.
            jdbc.execute(ClassPathResource("db/local/R__local_audio.sql").inputStream.readBytes().decodeToString())
        }

        @AfterAll
        @JvmStatic
        fun dropDatabase() {
            adminConnection().use { it.createStatement().execute("DROP DATABASE IF EXISTS $scratchDb WITH (FORCE)") }
        }
    }

    private val storage get() = LocalDbAudioStorage(jdbc)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `uploaded audio comes back byte for byte`() {
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        val result = storage.upload(UUID.randomUUID(), "talk.mp3", ByteArrayInputStream(bytes), bytes.size.toLong(), "audio/mpeg")

        assertThat(storage.download(result.ref)!!.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `the reference is a db uri with the date, the id and the file name`() {
        val id = UUID.randomUUID()
        val result = storage.upload(id, "talk.mp3", ByteArrayInputStream(ByteArray(4)), 4, "audio/mpeg")

        assertThat(result.ref.uri).startsWith("db://audio/").contains("/$id/").endsWith("/talk.mp3")
    }

    @Test
    fun `the content hash is the sha256 of the bytes`() {
        val bytes = "hello audio".toByteArray()
        val result = storage.upload(UUID.randomUUID(), "a.wav", ByteArrayInputStream(bytes), bytes.size.toLong(), "audio/wav")

        assertThat(result.contentHash).isEqualTo(sha256(bytes))
    }

    @Test
    fun `an object that is not there is null`() {
        assertThat(storage.download(AudioRef("db://audio/2000-01-01/${UUID.randomUUID()}/x.mp3"))).isNull()
    }

    @Test
    fun `openFrom returns everything from the given offset, not from the start`() {
        val bytes = ByteArray(20) { it.toByte() }
        val result = storage.upload(UUID.randomUUID(), "ranged.mp3", ByteArrayInputStream(bytes), bytes.size.toLong(), "audio/mpeg")

        assertThat(storage.openFrom(result.ref, 5)!!.readBytes()).isEqualTo(bytes.copyOfRange(5, 20))
        assertThat(storage.openFrom(result.ref, 0)!!.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `openFrom on an object that is not there is null`() {
        assertThat(storage.openFrom(AudioRef("db://audio/2000-01-01/${UUID.randomUUID()}/x.mp3"), 0)).isNull()
    }

    @Test
    fun `listing shows uploaded audio with its creation time and skips backups`() {
        val result = storage.upload(UUID.randomUUID(), "listed.mp3", ByteArrayInputStream(ByteArray(3)), 3, "audio/mpeg")
        val backupName = "backups/2025-01-01/${UUID.randomUUID()}.json.gz"
        storage.uploadBytes(backupName, byteArrayOf(1, 2, 3))

        val listed = storage.listObjects()

        val entry = listed.single { it.uri == result.ref.uri }
        assertThat(entry.createdAt).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60))
        assertThat(listed.map { it.uri }).doesNotContain("db://$backupName")
    }

    @Test
    fun `deleting removes the audio and is harmless twice`() {
        val result = storage.upload(UUID.randomUUID(), "gone.mp3", ByteArrayInputStream(ByteArray(3)), 3, "audio/mpeg")

        storage.delete(result.ref)
        storage.delete(result.ref)

        assertThat(storage.download(result.ref)).isNull()
        assertThat(storage.listObjects().map { it.uri }).doesNotContain(result.ref.uri)
    }

    @Test
    fun `uploadBytes stores a named object and overwrites it`() {
        val name = "backups/2025-01-01/${UUID.randomUUID()}.json.gz"
        storage.uploadBytes(name, byteArrayOf(1))
        storage.uploadBytes(name, byteArrayOf(9, 9))

        assertThat(storage.download(AudioRef("db://$name"))!!.readBytes()).containsExactly(9, 9)
    }
}
