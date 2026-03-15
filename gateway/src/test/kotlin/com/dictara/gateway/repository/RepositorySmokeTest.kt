package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RepositorySmokeTest {

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired lateinit var userRepo: UserRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @Autowired lateinit var submissionRepo: SubmissionRepository

    @Test
    fun `can create and retrieve a submission`() {
        val user = userRepo.save(UserEntity(displayName = "Test User"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            user = user, originalName = "test.m4a",
            contentType = "audio/mp4", sizeBytes = 1024,
        ))
        val submission = submissionRepo.save(SubmissionEntity(
            user = user, audio = audio, model = "fast",
            language = "auto", summaryMode = "off",
        ))

        val found = submissionRepo.findById(submission.id!!).orElse(null)
        assertThat(found).isNotNull
        assertThat(found.status).isEqualTo("pending")
        assertThat(found.audio.originalName).isEqualTo("test.m4a")
    }
}
