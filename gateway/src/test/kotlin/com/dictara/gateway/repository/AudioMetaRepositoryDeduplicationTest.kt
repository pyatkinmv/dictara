package com.dictara.gateway.repository

import com.dictara.gateway.SharedTestInfrastructure
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AudioMetaRepositoryDeduplicationTest {

    companion object {
        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            val pg = SharedTestInfrastructure.postgres
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE audio_meta CASCADE")
    }

    @Test
    fun `deduplicateStorageUris points all duplicates to oldest storage URI`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val hash = "aabbcc112233"
        val oldUri = "gs://bucket/audio/old/file.mp4"
        val newUri1 = "gs://bucket/audio/new1/file.mp4"
        val newUri2 = "gs://bucket/audio/new2/file.mp4"

        val base = Instant.parse("2026-01-01T00:00:00Z")
        audioMeta(user, hash, oldUri,  base)
        audioMeta(user, hash, newUri1, base.plusSeconds(60))
        audioMeta(user, hash, newUri2, base.plusSeconds(120))

        val updated = audioMetaRepo.deduplicateStorageUris()

        assertThat(updated).isEqualTo(2)
        audioMetaRepo.findAll().filter { it.contentHash == hash }.forEach { row ->
            assertThat(row.storageUri).isEqualTo(oldUri)
        }
    }

    @Test
    fun `deduplicateStorageUris does not touch rows with unique hash`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val uri = "gs://bucket/audio/unique/file.mp4"
        audioMeta(user, "unique-hash", uri, Instant.now())

        val updated = audioMetaRepo.deduplicateStorageUris()

        assertThat(updated).isEqualTo(0)
        assertThat(audioMetaRepo.findAll().first().storageUri).isEqualTo(uri)
    }

    @Test
    fun `deduplicateStorageUris is idempotent`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val hash = "idem-hash"
        val oldUri = "gs://bucket/audio/old/file.mp4"
        val base = Instant.parse("2026-01-01T00:00:00Z")
        audioMeta(user, hash, oldUri,                 base)
        audioMeta(user, hash, "gs://bucket/new.mp4",  base.plusSeconds(60))

        audioMetaRepo.deduplicateStorageUris()
        val secondRun = audioMetaRepo.deduplicateStorageUris()

        assertThat(secondRun).isEqualTo(0)
    }

    @Test
    fun `findAllStorageUris returns all non-null URIs`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        audioMeta(user, "hash1", "gs://bucket/a.mp4", Instant.now())
        audioMeta(user, "hash2", "gs://bucket/b.mp4", Instant.now())

        val uris = audioMetaRepo.findAllStorageUris()

        assertThat(uris).containsExactlyInAnyOrder("gs://bucket/a.mp4", "gs://bucket/b.mp4")
    }

    private fun audioMeta(user: UserEntity, hash: String, uri: String, createdAt: Instant) =
        audioMetaRepo.save(AudioMetaEntity(
            userId = user.id!!, originalName = "file.mp4", contentType = "video/mp4",
            sizeBytes = 1024, contentHash = hash, storageUri = uri, createdAt = createdAt,
        ).apply { _isNew = true })
}
