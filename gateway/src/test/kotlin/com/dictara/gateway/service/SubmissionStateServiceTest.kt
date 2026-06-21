package com.dictara.gateway.service

import com.dictara.gateway.SharedTestInfrastructure
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import javax.sql.DataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@SpringBootTest(webEnvironment = NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SubmissionStateServiceTest {

    companion object {
        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            val pg = SharedTestInfrastructure.postgres
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
        }
    }

    @Autowired lateinit var stateService: SubmissionStateService
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @Autowired lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanDb() {
        dataSource.connection.use { conn ->
            conn.createStatement().execute(
                "TRUNCATE TABLE stage_attempts, telegram_deliveries, submission_tags, " +
                "diarizations, summaries, transcripts, audio_meta, submissions CASCADE"
            )
        }
    }

    @Test
    fun `claimNextPendingSubmission returns null when no submissions exist`() {
        val result = stateService.claimNextPendingSubmission()
        assertThat(result).isNull()
    }

    @Test
    fun `loadSubmission returns entity with EAGER associations accessible outside transaction`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            user = user, originalName = "a.m4a", contentType = "audio/mp4", sizeBytes = 100, contentHash = "abc123",
        ))
        val saved = submissionRepo.save(SubmissionEntity(user = user, audio = audio))
        val submissionId = saved.id!!

        // loadSubmission returns entity with associations accessible (no LazyInitializationException)
        val loaded = stateService.loadSubmission(submissionId)
        assertThat(loaded.audio.originalName).isEqualTo("a.m4a")
        assertThat(loaded.user.displayName).isEqualTo("Test")
    }
}
