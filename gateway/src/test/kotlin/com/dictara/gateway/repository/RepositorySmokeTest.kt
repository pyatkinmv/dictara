package com.dictara.gateway.repository

import com.dictara.gateway.SharedTestInfrastructure
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositorySmokeTest {

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

    @Test
    fun `can create and retrieve a submission`() {
        val user = userRepo.save(UserEntity(displayName = "Test User"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            userId = user.id!!, originalName = "test.m4a",
            contentType = "audio/mp4", sizeBytes = 1024, contentHash = "abc123",
        ).apply { _isNew = true })
        val submission = submissionRepo.save(SubmissionEntity(
            userId = user.id!!, audioId = audio.id, model = "fast",
            language = "auto", summaryMode = "off",
        ))

        val found = submissionRepo.findById(submission.id!!).orElse(null)
        assertThat(found).isNotNull
        assertThat(found.status).isEqualTo("pending")
        val foundAudio = audioMetaRepo.findById(found.audioId).orElseThrow()
        assertThat(foundAudio.originalName).isEqualTo("test.m4a")
    }
}
