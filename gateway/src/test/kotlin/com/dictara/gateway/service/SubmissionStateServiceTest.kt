package com.dictara.gateway.service

import com.dictara.gateway.AbstractSharedContextIntegrationTest
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.*
import com.dictara.gateway.model.Segment
import com.fasterxml.jackson.databind.ObjectMapper
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
    @Autowired lateinit var transcriptRepo: TranscriptRepository
    @Autowired lateinit var stageAttemptRepo: StageAttemptRepository
    @Autowired lateinit var segmentQueries: TranscriptSegmentQueries

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
        ).apply { _isNew = true })
        val saved = submissionRepo.save(SubmissionEntity(userId = user.id!!, audioId = audio.id))
        val submissionId = saved.id!!

        val loaded = stateService.loadSubmission(submissionId)
        assertThat(loaded.audioId).isEqualTo(audio.id)
        assertThat(loaded.userId).isEqualTo(user.id!!)
    }

    @Test
    fun `saveTranscriptAndCompleteAttempt persists duration_s to audio_meta and detected_language to transcript`() {
        val user = userRepo.save(UserEntity(displayName = "Test"))
        val audio = audioMetaRepo.save(AudioMetaEntity(
            userId = user.id!!, originalName = "a.m4a", contentType = "audio/mp4", sizeBytes = 100, contentHash = "xyz999",
        ).apply { _isNew = true })
        val submission = submissionRepo.save(SubmissionEntity(userId = user.id!!, audioId = audio.id))
        val submissionId = submission.id!!
        val attempt = stateService.createAttempt(submissionId, "transcription")
        val segments = listOf(Segment(0.0, 2.0, "Hello", "SPEAKER_00"), Segment(2.5, 4.0, "World", null))

        stateService.saveTranscriptAndCompleteAttempt(
            submissionId = submissionId,
            audioId = audio.id,
            attemptId = attempt.id!!,
            segments = segments,
            audioDurationS = 7.5,
            detectedLanguage = "ru",
        )

        val savedAudio = audioMetaRepo.findById(audio.id).orElseThrow()
        assertThat(savedAudio.durationS).isEqualTo(7.5)

        val savedTranscript = transcriptRepo.findBySubmissionId(submissionId)
        assertThat(savedTranscript?.detectedLanguage).isEqualTo("ru")
        assertThat(savedTranscript?.segments).isNotNull

        // The phrases are stored as rows, in order, and (for one more release) also as the old JSON.
        assertThat(segmentQueries.findByTranscriptId(savedTranscript!!.id!!)).isEqualTo(segments)
        val json = savedTranscript.segments!!
        assertThat(json.size()).isEqualTo(2)
        assertThat(json[0]["text"].asText()).isEqualTo("Hello")
        assertThat(json[0]["speaker"].asText()).isEqualTo("SPEAKER_00")
        assertThat(json[1]["speaker"].isNull).isTrue()
        assertThat(json[1]["start"].asDouble()).isEqualTo(2.5)

        val savedAttempt = stageAttemptRepo.findById(attempt.id!!).orElseThrow()
        assertThat(savedAttempt.status).isEqualTo("done")
    }
}
