package com.dictara.gateway.service

import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest(webEnvironment = NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SubmissionStateServiceTest {

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var stateService: SubmissionStateService
    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @Autowired lateinit var submissionRepo: SubmissionRepository

    @Test
    fun `claimNextPendingSubmission returns null when no submissions exist`() {
        val result = stateService.claimNextPendingSubmission()
        assertThat(result).isNull()
    }

    @Test
    fun `loadSubmission returns entity with EAGER associations accessible outside transaction`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            user = user, originalName = "a.m4a", contentType = "audio/mp4", sizeBytes = 100,
        ))
        val saved = submissionRepo.save(SubmissionEntity(user = user, audio = audio))
        val submissionId = saved.id!!

        // loadSubmission returns entity with associations accessible (no LazyInitializationException)
        val loaded = stateService.loadSubmission(submissionId)
        assertThat(loaded.audio.originalName).isEqualTo("a.m4a")
        assertThat(loaded.user.displayName).isEqualTo("Test")
    }
}
