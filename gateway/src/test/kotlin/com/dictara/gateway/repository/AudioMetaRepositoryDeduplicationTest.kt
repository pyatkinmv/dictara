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
            registry.add("spring.datasource.url") { SharedTestInfrastructure.jdbcUrl }
            registry.add("spring.datasource.username") { SharedTestInfrastructure.dbUsername }
            registry.add("spring.datasource.password") { SharedTestInfrastructure.dbPassword }
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

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `deduplicateStorageUris points all duplicates to the oldest live storage URI`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val hash = "aabbcc112233"
        val oldUri = "gs://bucket/audio/old/file.mp4"
        val newUri1 = "gs://bucket/audio/new1/file.mp4"
        val newUri2 = "gs://bucket/audio/new2/file.mp4"
        audioMeta(user, hash, oldUri, base)
        audioMeta(user, hash, newUri1, base.plusSeconds(60))
        audioMeta(user, hash, newUri2, base.plusSeconds(120))

        val updated = audioMetaRepo.deduplicateStorageUris(listOf(oldUri, newUri1, newUri2))

        assertThat(updated).isEqualTo(2)
        audioMetaRepo.findAll().filter { it.contentHash == hash }.forEach { assertThat(it.storageUri).isEqualTo(oldUri) }
    }

    @Test
    fun `a dead oldest object is not made the canonical one, the oldest live one is`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val hash = "dead-canonical"
        val dead = "gs://bucket/audio/2026-03-26/dead.m4a"
        val live1 = "gs://bucket/audio/2026-10-14/live1.m4a"
        val live2 = "gs://bucket/audio/2026-10-15/live2.m4a"
        audioMeta(user, hash, dead, base)
        audioMeta(user, hash, live1, base.plusSeconds(60))
        audioMeta(user, hash, live2, base.plusSeconds(120))

        audioMetaRepo.deduplicateStorageUris(listOf(live1, live2))

        // The old behaviour pointed everything at `dead`, the unreachable oldest row, which also let the orphan
        // cleanup delete the live copies.
        audioMetaRepo.findAll().filter { it.contentHash == hash }.forEach { assertThat(it.storageUri).isEqualTo(live1) }
    }

    @Test
    fun `a hash with no live object is left alone`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val deadUri = "gs://bucket/audio/dead/file.mp4"
        audioMeta(user, "all-dead", deadUri, base)
        audioMeta(user, "all-dead", "gs://bucket/audio/dead2/file.mp4", base.plusSeconds(60))
        audioMeta(user, "other", "gs://bucket/audio/other.mp4", base)

        val updated = audioMetaRepo.deduplicateStorageUris(listOf("gs://bucket/audio/other.mp4"))

        assertThat(updated).isEqualTo(0)
        assertThat(audioMetaRepo.findAll().filter { it.contentHash == "all-dead" }.map { it.storageUri })
            .containsExactlyInAnyOrder(deadUri, "gs://bucket/audio/dead2/file.mp4")
    }

    @Test
    fun `deduplicateStorageUris does not touch rows with unique hash`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val uri = "gs://bucket/audio/unique/file.mp4"
        audioMeta(user, "unique-hash", uri, Instant.now())

        val updated = audioMetaRepo.deduplicateStorageUris(listOf(uri))

        assertThat(updated).isEqualTo(0)
        assertThat(audioMetaRepo.findAll().first().storageUri).isEqualTo(uri)
    }

    @Test
    fun `deduplicateStorageUris is idempotent`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val hash = "idem-hash"
        val oldUri = "gs://bucket/audio/old/file.mp4"
        audioMeta(user, hash, oldUri, base)
        audioMeta(user, hash, "gs://bucket/new.mp4", base.plusSeconds(60))
        val live = listOf(oldUri, "gs://bucket/new.mp4")

        audioMetaRepo.deduplicateStorageUris(live)
        val secondRun = audioMetaRepo.deduplicateStorageUris(live)

        assertThat(secondRun).isEqualTo(0)
    }

    @Test
    fun `updateStorageUri repoints exactly the given rows`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val a = audioMeta(user, "h1", "gs://bucket/a", base)
        val b = audioMeta(user, "h1", "gs://bucket/b", base.plusSeconds(1))
        val c = audioMeta(user, "h2", "gs://bucket/c", base)

        audioMetaRepo.updateStorageUri(listOf(a.id, b.id), "gs://bucket/new")

        assertThat(audioMetaRepo.findById(a.id).get().storageUri).isEqualTo("gs://bucket/new")
        assertThat(audioMetaRepo.findById(b.id).get().storageUri).isEqualTo("gs://bucket/new")
        assertThat(audioMetaRepo.findById(c.id).get().storageUri).isEqualTo("gs://bucket/c")
    }

    @Test
    fun `findByContentHashOrderByCreatedAtAsc is oldest first and exact`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        audioMeta(user, "same", "gs://bucket/2", base.plusSeconds(60))
        audioMeta(user, "same", "gs://bucket/1", base)
        audioMeta(user, "different", "gs://bucket/x", base)

        assertThat(audioMetaRepo.findByContentHashOrderByCreatedAtAsc("same").map { it.storageUri })
            .containsExactly("gs://bucket/1", "gs://bucket/2")
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
