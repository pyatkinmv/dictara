package com.dictara.gateway.service

import com.dictara.gateway.AbstractSharedContextIntegrationTest
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import java.util.UUID

@SpringBootTest(webEnvironment = RANDOM_PORT)
class SubmissionStateServiceTest : AbstractSharedContextIntegrationTest() {

    @MockBean lateinit var audioStorage: AudioStorage

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
    fun `loadSubmission returns submission with correct FK references`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            userId = user.id!!, originalName = "a.m4a", contentType = "audio/mp4", sizeBytes = 100, contentHash = "abc123",
        ))
        val saved = submissionRepo.save(SubmissionEntity(userId = user.id!!, audioId = audio.id!!))
        val submissionId = saved.id!!

        val loaded = stateService.loadSubmission(submissionId)
        assertThat(loaded.audioId).isEqualTo(audio.id)
        assertThat(loaded.userId).isEqualTo(user.id)
    }
}
