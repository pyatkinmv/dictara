package com.dictara.gateway.repository

import com.dictara.gateway.SharedTestInfrastructure
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.service.ListSort
import com.dictara.gateway.service.TranscriptionListFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** `audio_meta.recorded_at`: which source may replace which, and the two list orders that use it. */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AudioMetaRecordedAtTest {

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
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var namedJdbc: NamedParameterJdbcTemplate

    private lateinit var user: UserEntity
    private val t0 = Instant.parse("2026-06-08T10:00:00Z")

    @BeforeEach
    fun clean() {
        jdbcTemplate.execute("TRUNCATE audio_meta CASCADE")
        user = userRepo.save(UserEntity(displayName = "Test"))
    }

    private fun audio(name: String = "a.m4a") = audioMetaRepo.save(AudioMetaEntity(
        userId = user.id!!, originalName = name, contentType = "audio/mp4", sizeBytes = 1,
        contentHash = UUID.randomUUID().toString(),
    ).apply { _isNew = true })

    private fun reload(a: AudioMetaEntity) = audioMetaRepo.findById(a.id).orElseThrow()

    // -- which source wins -----------------------------------------------------------------------------------

    @Test
    fun `a date from the name fills an empty value`() {
        val a = audio()
        audioMetaRepo.updateRecordedAtFromFileName(a.id, t0)

        assertThat(reload(a).recordedAt).isEqualTo(t0)
        assertThat(reload(a).recordedAtSource).isEqualTo("filename")
    }

    @Test
    fun `the container time replaces a date from the name`() {
        val a = audio()
        audioMetaRepo.updateRecordedAtFromFileName(a.id, t0)
        audioMetaRepo.updateRecordedAtFromMetadata(a.id, t0.plusSeconds(600))

        assertThat(reload(a).recordedAt).isEqualTo(t0.plusSeconds(600))
        assertThat(reload(a).recordedAtSource).isEqualTo("metadata")
    }

    @Test
    fun `a date from the name never replaces the container time`() {
        val a = audio()
        audioMetaRepo.updateRecordedAtFromMetadata(a.id, t0)
        audioMetaRepo.updateRecordedAtFromFileName(a.id, t0.plusSeconds(600))

        assertThat(reload(a).recordedAt).isEqualTo(t0)
        assertThat(reload(a).recordedAtSource).isEqualTo("metadata")
    }

    @Test
    fun `a manual value is replaced by nothing`() {
        val a = audio()
        jdbcTemplate.update("UPDATE audio_meta SET recorded_at = ?, recorded_at_source = 'manual' WHERE id = ?", Timestamp.from(t0), a.id)
        audioMetaRepo.updateRecordedAtFromMetadata(a.id, t0.plusSeconds(600))
        audioMetaRepo.updateRecordedAtFromFileName(a.id, t0.plusSeconds(1200))

        assertThat(reload(a).recordedAt).isEqualTo(t0)
        assertThat(reload(a).recordedAtSource).isEqualTo("manual")
    }

    @Test
    fun `a source outside the three is refused by the database`() {
        val a = audio()
        assertThrows<Exception> {
            jdbcTemplate.update("UPDATE audio_meta SET recorded_at_source = 'guess' WHERE id = ?", a.id)
        }
    }

    // -- the list orders -------------------------------------------------------------------------------------

    private fun submission(a: AudioMetaEntity, uploaded: Instant) = submissionRepo.save(SubmissionEntity(
        userId = user.id!!, audioId = a.id, createdAt = uploaded, status = "done",
    )).id!!

    private fun order(sort: ListSort) =
        SubmissionListQuery(namedJdbc).findIds(user.id!!, TranscriptionListFilter(sort = sort)).ids

    @Test
    fun `sorting by recording time uses the upload time for a recording whose time is not known`() {
        val a1 = audio(); val a2 = audio(); val a3 = audio(); val a4 = audio()
        val s1 = submission(a1, t0.plusSeconds(100)); val s2 = submission(a2, t0.plusSeconds(200))
        val s3 = submission(a3, t0.plusSeconds(300)); val s4 = submission(a4, t0.plusSeconds(400))
        audioMetaRepo.updateRecordedAtFromMetadata(a1.id, t0.minusSeconds(86_400 * 30))
        audioMetaRepo.updateRecordedAtFromMetadata(a3.id, t0.plusSeconds(250))
        audioMetaRepo.updateRecordedAtFromMetadata(a4.id, t0.minusSeconds(86_400 * 400))

        // times used: s4 recorded 400 days before, s1 30 days before, s2 (unknown) uploaded at +200 s, s3 recorded at +250 s
        assertThat(order(ListSort.RECORDED_NEWEST)).containsExactly(s3, s2, s1, s4)
        assertThat(order(ListSort.RECORDED_OLDEST)).containsExactly(s4, s1, s2, s3)
        // the upload orders do not care about the recording time
        assertThat(order(ListSort.NEWEST)).containsExactly(s4, s3, s2, s1)
        assertThat(order(ListSort.OLDEST)).containsExactly(s1, s2, s3, s4)
    }
}
