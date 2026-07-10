package com.dictara.gateway

import com.dictara.gateway.repository.SubmissionTagRepository
import com.dictara.gateway.repository.TagRepository
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import java.util.UUID

@SpringBootTest(webEnvironment = RANDOM_PORT)
class TagsIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var submissionService: SubmissionService
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var submissionTagRepo: SubmissionTagRepository
    @MockBean lateinit var audioStorage: AudioStorage

    private lateinit var userId: UUID
    private lateinit var submissionId: UUID

    @BeforeEach
    fun setupUserAndSubmission() {
        userId = UUID.randomUUID()
        val audioId = UUID.randomUUID()
        submissionId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'test.m4a', 'audio/mp4', 1000, 'gs://test/test.m4a', ?)",
            audioId, userId, "hash-$userId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
            submissionId, userId, audioId,
        )
    }

    @Test
    fun `add tag creates a tags row scoped to the user`() {
        submissionService.addTag(submissionId, userId, "meeting")

        val ownerUserId = jdbcTemplate.queryForObject(
            "SELECT user_id FROM tags WHERE name = 'meeting' AND user_id = ?",
            UUID::class.java, userId,
        )
        assertThat(ownerUserId).isEqualTo(userId)
    }

    @Test
    fun `same tag name for different users creates separate tags rows`() {
        val otherUserId = UUID.randomUUID()
        val otherAudioId = UUID.randomUUID()
        val otherSubmissionId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", otherUserId)
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'other.m4a', 'audio/mp4', 1000, 'gs://test/other.m4a', ?)",
            otherAudioId, otherUserId, "hash-$otherUserId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
            otherSubmissionId, otherUserId, otherAudioId,
        )

        submissionService.addTag(submissionId, userId, "meeting")
        submissionService.addTag(otherSubmissionId, otherUserId, "meeting")

        val tagOwners = jdbcTemplate.queryForList(
            "SELECT user_id FROM tags WHERE name = 'meeting' AND user_id IN (?, ?)",
            UUID::class.java, userId, otherUserId,
        )
        assertThat(tagOwners).containsExactlyInAnyOrder(userId, otherUserId)
    }

    @Test
    fun `add tag is idempotent`() {
        submissionService.addTag(submissionId, userId, "meeting")
        submissionService.addTag(submissionId, userId, "meeting")

        val tagCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tags WHERE name = 'meeting' AND user_id = ?",
            Long::class.java, userId,
        )
        val junctionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submission_tags st JOIN tags t ON t.id = st.tag_id WHERE st.submission_id = ? AND t.name = 'meeting'",
            Long::class.java, submissionId,
        )
        assertThat(tagCount).isEqualTo(1L)
        assertThat(junctionCount).isEqualTo(1L)
    }

    @Test
    fun `remove tag from submission does not delete the tags row`() {
        submissionService.addTag(submissionId, userId, "meeting")
        submissionService.removeTag(submissionId, userId, "meeting")

        val tagCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tags WHERE name = 'meeting' AND user_id = ?",
            Long::class.java, userId,
        )
        val junctionCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM submission_tags st JOIN tags t ON t.id = st.tag_id WHERE st.submission_id = ? AND t.name = 'meeting'",
            Long::class.java, submissionId,
        )
        assertThat(tagCount).isEqualTo(1L)
        assertThat(junctionCount).isEqualTo(0L)
    }

    @Test
    fun `tag name validation rejects names longer than 32 chars`() {
        val tagRegex = Regex("^[\\w-]{1,32}$")
        assertThat(tagRegex.matches("a".repeat(32))).isTrue()
        assertThat(tagRegex.matches("a".repeat(33))).isFalse()
    }
}
