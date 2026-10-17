package com.dictara.gateway.service

import com.dictara.gateway.AbstractSharedContextIntegrationTest
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import java.util.UUID

/** The same bytes arriving again after the stored copy is gone: every record of that content gets a live file. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class AudioRestoreServiceTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var restore: AudioRestoreService
    @MockBean lateinit var audioStorage: AudioStorage

    private val hash = "restore-hash-1"
    private val deadUri = "gs://b/audio/2026-03-26/dead.m4a"
    private val freshUri = "gs://b/audio/2026-10-14/fresh.m4a"
    private lateinit var alice: UUID
    private lateinit var bob: UUID

    @BeforeEach
    fun setUp() {
        alice = newUser(); bob = newUser()
        given(audioStorage.isGone(AudioRef(deadUri))).willReturn(true)
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    /** An audio record and one submission on it; returns (audio id, submission id). */
    private fun record(owner: UUID, uri: String?, status: String = "done", contentHash: String = hash, name: String = "a.m4a"): Pair<UUID, UUID> {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 10, ?, ?)",
            audioId, owner, name, uri, contentHash,
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'turbo', 'auto', true, 'auto', 'telegram', ?)",
            submissionId, owner, audioId, status,
        )
        return audioId to submissionId
    }

    private fun nameOf(audioId: UUID) =
        jdbcTemplate.queryForObject("SELECT original_name FROM audio_meta WHERE id = ?", String::class.java, audioId)

    private fun uriOf(audioId: UUID) =
        jdbcTemplate.queryForObject("SELECT storage_uri FROM audio_meta WHERE id = ?", String::class.java, audioId)

    @Test
    fun `a record whose file is gone adopts the fresh upload and the existing submission is returned`() {
        val (audio, submission) = record(alice, deadUri)

        val answer = restore.restore(alice, hash, freshUri)

        assertThat(answer).isEqualTo(submission)
        assertThat(uriOf(audio)).isEqualTo(freshUri)
    }

    @Test
    fun `every record of that content is healed, whoever owns it and whichever options it was made with`() {
        val otherDead = "gs://b/audio/other-dead.m4a"
        given(audioStorage.isGone(AudioRef(otherDead))).willReturn(true)
        val (a1, s1) = record(alice, deadUri)
        val (a2, s2) = record(alice, otherDead)
        val (a3, _) = record(bob, deadUri)

        val answer = restore.restore(alice, hash, freshUri)

        assertThat(listOf(a1, a2, a3).map { uriOf(it) }).containsOnly(freshUri)
        assertThat(answer).isIn(s1, s2)
    }

    @Test
    fun `when a live copy exists the dead records point at it and the fresh upload is not used`() {
        val live = "gs://b/audio/2026-10-01/live.m4a"
        val (liveAudio, _) = record(bob, live)
        val (dead, _) = record(alice, deadUri)

        restore.restore(alice, hash, freshUri)

        assertThat(uriOf(dead)).isEqualTo(live)
        assertThat(uriOf(liveAudio)).isEqualTo(live)
    }

    @Test
    fun `nothing is missing, nothing changes and no existing submission is returned`() {
        val (audio, _) = record(alice, "gs://b/audio/live.m4a")

        assertThat(restore.restore(alice, hash, freshUri)).isNull()
        assertThat(uriOf(audio)).isEqualTo("gs://b/audio/live.m4a")
    }

    @Test
    fun `another user's lost audio is healed but is not an answer for this user`() {
        val (bobAudio, _) = record(bob, deadUri)

        assertThat(restore.restore(alice, hash, freshUri)).isNull()
        assertThat(uriOf(bobAudio)).isEqualTo(freshUri)
    }

    @Test
    fun `a failed submission is healed but never returned as the answer`() {
        val (audio, _) = record(alice, deadUri, status = "failed")

        assertThat(restore.restore(alice, hash, freshUri)).isNull()
        assertThat(uriOf(audio)).isEqualTo(freshUri)
    }

    @Test
    fun `a record with no file reference at all counts as lost`() {
        val (audio, submission) = record(alice, null)

        assertThat(restore.restore(alice, hash, freshUri)).isEqualTo(submission)
        assertThat(uriOf(audio)).isEqualTo(freshUri)
    }

    @Test
    fun `content nobody has seen is not a restore`() {
        record(alice, deadUri, contentHash = "someone-else")

        assertThat(restore.restore(alice, "brand-new-hash", freshUri)).isNull()
    }

    @Test
    fun `a made-up name of the uploader's own record is replaced by the real one, live audio or not`() {
        val (temp, _) = record(alice, "gs://b/audio/live.m4a", name = "dictara-7802649983095675646.m4a")
        val (voice, _) = record(alice, deadUri, name = "audio_1792062859802.oga")

        restore.adoptOriginalName(alice, hash, "20260608_213900.m4a")

        assertThat(nameOf(temp)).isEqualTo("20260608_213900.m4a")
        assertThat(nameOf(voice)).isEqualTo("20260608_213900.m4a")
    }

    @Test
    fun `a name the user kept is left alone`() {
        val (audio, _) = record(alice, "gs://b/audio/live.m4a", name = "Interview with Anna.m4a")

        restore.adoptOriginalName(alice, hash, "20260608_213900.m4a")

        assertThat(nameOf(audio)).isEqualTo("Interview with Anna.m4a")
    }

    @Test
    fun `another user's record keeps its name`() {
        val (bobAudio, _) = record(bob, "gs://b/audio/live.m4a", name = "dictara-123.m4a")

        restore.adoptOriginalName(alice, hash, "20260608_213900.m4a")

        assertThat(nameOf(bobAudio)).isEqualTo("dictara-123.m4a")
    }

    @Test
    fun `a new name that is made up as well changes nothing`() {
        val (audio, _) = record(alice, "gs://b/audio/live.m4a", name = "dictara-123.m4a")

        restore.adoptOriginalName(alice, hash, "dictara-999.m4a")

        assertThat(nameOf(audio)).isEqualTo("dictara-123.m4a")
    }

    @Test
    fun `only the made-up shapes count as made up`() {
        assertThat(listOf("dictara-7802649983095675646.m4a", "dictara-1.oga", "audio_1792062859802.oga", "audio_17")
            .all { AudioRestoreService.isMadeUpName(it) }).isTrue()
        assertThat(listOf("20260608_213900.m4a", "audio_2026-09-20_22-32-32.ogg", "dictara-notes.m4a", "my audio_1.m4a", "audio.m4a")
            .none { AudioRestoreService.isMadeUpName(it) }).isTrue()
    }
}
