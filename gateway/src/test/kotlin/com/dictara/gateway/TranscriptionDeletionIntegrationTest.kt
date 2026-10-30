package com.dictara.gateway

import com.dictara.gateway.plan.PlanService
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.server.ResponseStatusException
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** `DELETE /transcriptions/{id}`: what goes, what stays when the audio is shared, who may, and that the allowance is not given back. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionDeletionIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var plans: PlanService
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @MockBean lateinit var audioStorage: AudioStorage

    @BeforeEach
    fun resetStorageMock() {
        reset(audioStorage)
    }

    private fun newUser(plan: String = "unlimited"): UUID =
        UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id, plan) VALUES (?, ?)", it, plan) }

    private fun delete(user: UUID?, id: String): Int {
        val headers = HttpHeaders().apply { user?.let { setBearerAuth(jwt.issue(it)) } }
        return rest.exchange("/transcriptions/$id", HttpMethod.DELETE, HttpEntity<Void>(headers), String::class.java).statusCode.value()
    }

    private fun get(user: UUID, id: UUID): Int {
        val headers = HttpHeaders().apply { setBearerAuth(jwt.issue(user)) }
        return rest.exchange("/transcriptions/$id", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).statusCode.value()
    }

    /** One audio row (its own file unless [uri] and [hash] say otherwise). */
    private fun audio(owner: UUID, name: String, uri: String = "gs://test/${UUID.randomUUID()}", hash: String = "hash-${UUID.randomUUID()}"): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?, 60)",
            id, owner, name, uri, hash,
        )
        return id
    }

    /** A submission on [audioId] with a transcript of two phrases and a summary; returns the submission id. */
    private fun submission(
        owner: UUID,
        audioId: UUID,
        status: String = "done",
        createdAt: Instant = Instant.now(),
        regenerating: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at, summary_regeneration) VALUES (?, ?, ?, 'fast', 'auto', true, 'auto', 'web', ?, ?, ?)",
            id, owner, audioId, status, Timestamp.from(createdAt), if (regenerating) "running" else null,
        )
        if (status == "done") {
            insertTranscript(
                UUID.randomUUID(), id,
                """[{"start":0.0,"end":1.0,"text":"Hello","speaker":"SPEAKER_00"},{"start":1.0,"end":2.0,"text":"World","speaker":"SPEAKER_01"}]""",
                title = "Title of $id", detectedLanguage = "en",
            )
            jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, 'A summary')", id)
        }
        return id
    }

    private fun count(sql: String, vararg args: Any): Int = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    // -- what goes ------------------------------------------------------------------------------------------

    @Test
    fun `deleting removes the transcript, phrases, summary, tags and speaker marks, the audio row and the stored file`() {
        val user = newUser()
        val audio = audio(user, "call.m4a", uri = "gs://test/call")
        val id = submission(user, audio)
        val tag = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, 'budget')", it, user) }
        val speaker = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, 'Anna')", it, user) }
        jdbcTemplate.update("INSERT INTO transcript_tags(transcript_id, tag_id) SELECT id, ? FROM transcripts WHERE submission_id = ?", tag, id)
        jdbcTemplate.update("INSERT INTO transcript_voices(transcript_id, voice, speaker_id) SELECT id, 'SPEAKER_00', ? FROM transcripts WHERE submission_id = ?", speaker, id)

        assertThat(delete(user, id.toString())).isEqualTo(204)

        assertThat(count("SELECT count(*) FROM submissions WHERE id = ?", id)).isZero()
        assertThat(count("SELECT count(*) FROM transcripts WHERE submission_id = ?", id)).isZero()
        assertThat(count("SELECT count(*) FROM transcript_segments")).isZero()
        assertThat(count("SELECT count(*) FROM summaries WHERE submission_id = ?", id)).isZero()
        assertThat(count("SELECT count(*) FROM transcript_tags")).isZero()
        assertThat(count("SELECT count(*) FROM transcript_voices")).isZero()
        assertThat(count("SELECT count(*) FROM audio_meta WHERE id = ?", audio)).isZero()
        verify(audioStorage).delete(AudioRef("gs://test/call"))
        assertThat(get(user, id)).isEqualTo(404)
        // The library is the user's own, and stays.
        assertThat(count("SELECT count(*) FROM tags WHERE id = ?", tag)).isEqualTo(1)
        assertThat(count("SELECT count(*) FROM speakers WHERE id = ?", speaker)).isEqualTo(1)
    }

    @Test
    fun `a transcription that came from the Telegram bot can be deleted, its delivery goes with it`() {
        val user = newUser()
        val id = submission(user, audio(user, "voice.oga"))
        jdbcTemplate.update("INSERT INTO telegram_deliveries(job_id, chat_id) VALUES (?, 42)", id)

        assertThat(delete(user, id.toString())).isEqualTo(204)

        assertThat(count("SELECT count(*) FROM telegram_deliveries WHERE job_id = ?", id)).isZero()
    }

    @Test
    fun `a failed transcription can be deleted too, and it is not remembered for the allowance`() {
        val user = newUser()
        val id = submission(user, audio(user, "bad.m4a"), status = "failed")

        assertThat(delete(user, id.toString())).isEqualTo(204)

        assertThat(count("SELECT count(*) FROM deleted_submissions WHERE user_id = ?", user)).isZero()
    }

    // -- shared audio -----------------------------------------------------------------------------------------

    @Test
    fun `audio that another record of the same user still uses stays, with its file`() {
        val user = newUser()
        val audio = audio(user, "call.m4a", uri = "gs://test/shared")
        val first = submission(user, audio)
        val second = submission(user, audio)

        assertThat(delete(user, first.toString())).isEqualTo(204)

        assertThat(count("SELECT count(*) FROM audio_meta WHERE id = ?", audio)).isEqualTo(1)
        verify(audioStorage, never()).delete(AudioRef("gs://test/shared"))
        assertThat(get(user, second)).isEqualTo(200)
    }

    @Test
    fun `a file that another user's record points at is kept, and that user's transcription is untouched`() {
        val mine = newUser()
        val theirs = newUser()
        val myAudio = audio(mine, "same.m4a", uri = "gs://test/one-object", hash = "same-content")
        val theirAudio = audio(theirs, "same.m4a", uri = "gs://test/one-object", hash = "same-content")
        val myId = submission(mine, myAudio)
        val theirId = submission(theirs, theirAudio)

        assertThat(delete(mine, myId.toString())).isEqualTo(204)

        assertThat(count("SELECT count(*) FROM audio_meta WHERE id = ?", myAudio)).isZero()
        verify(audioStorage, never()).delete(AudioRef("gs://test/one-object"))
        assertThat(get(theirs, theirId)).isEqualTo(200)

        assertThat(delete(theirs, theirId.toString())).isEqualTo(204)
        verify(audioStorage).delete(AudioRef("gs://test/one-object")) // the last one takes the file with it
    }

    // -- who and when ----------------------------------------------------------------------------------------------

    @Test
    fun `someone else's transcription, an unknown id and a malformed id are all not found, and nothing is deleted`() {
        val owner = newUser()
        val stranger = newUser()
        val id = submission(owner, audio(owner, "private.m4a"))

        assertThat(delete(stranger, id.toString())).isEqualTo(404)
        assertThat(delete(owner, UUID.randomUUID().toString())).isEqualTo(404)
        assertThat(delete(owner, "not-an-id")).isEqualTo(404)

        assertThat(get(owner, id)).isEqualTo(200)
        verifyNoInteractions(audioStorage)
    }

    @Test
    fun `without a session it is unauthorized`() {
        assertThat(delete(null, UUID.randomUUID().toString())).isEqualTo(401)
    }

    @Test
    fun `while it is processed, or its summary is regenerated, it cannot be deleted`() {
        val user = newUser()
        val pending = submission(user, audio(user, "a.m4a"), status = "pending")
        val processing = submission(user, audio(user, "b.m4a"), status = "processing")
        val summarizing = submission(user, audio(user, "c.m4a"), status = "summarizing")
        val regenerating = submission(user, audio(user, "d.m4a"), regenerating = true)

        for (id in listOf(pending, processing, summarizing, regenerating)) {
            assertThat(delete(user, id.toString())).`as`(id.toString()).isEqualTo(409)
            assertThat(count("SELECT count(*) FROM submissions WHERE id = ?", id)).isEqualTo(1)
        }
        verifyNoInteractions(audioStorage)
    }

    @Test
    fun `an MCP token cannot delete`() {
        val user = newUser()
        val id = submission(user, audio(user, "e.m4a"))
        val token = "dtr_" + "x".repeat(43)

        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val status = rest.exchange("/transcriptions/$id", HttpMethod.DELETE, HttpEntity<Void>(headers), String::class.java).statusCode.value()

        assertThat(status).isEqualTo(401)
        assertThat(count("SELECT count(*) FROM submissions WHERE id = ?", id)).isEqualTo(1)
    }

    // -- the allowance ---------------------------------------------------------------------------------------------

    @Test
    fun `deleting does not give the allowance back, the lifetime trial of a guest stays used`() {
        val guest = newUser("anonymous") // 2 transcriptions ever
        val first = submission(guest, audio(guest, "1.m4a"))
        submission(guest, audio(guest, "2.m4a"))
        assertThatThrownBy { plans.enforce(guest) }.isInstanceOf(ResponseStatusException::class.java)

        assertThat(delete(guest, first.toString())).isEqualTo(204)

        assertThat(submissionRepo.countAllSubmissions(guest)).isEqualTo(2)
        assertThatThrownBy { plans.enforce(guest) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("free trial")
    }

    @Test
    fun `deleting does not give the month's allowance back either, but last month's deletions do not count this month`() {
        val user = newUser("free") // 10 a month
        val thisMonth = submission(user, audio(user, "now.m4a"))
        val lastMonth = submission(user, audio(user, "old.m4a"), createdAt = Instant.now().minus(45, ChronoUnit.DAYS))
        assertThat(submissionRepo.countCurrentMonthSubmissions(user)).isEqualTo(1)

        assertThat(delete(user, thisMonth.toString())).isEqualTo(204)
        assertThat(delete(user, lastMonth.toString())).isEqualTo(204)

        assertThat(submissionRepo.countCurrentMonthSubmissions(user)).isEqualTo(1) // the one made this month, though deleted
        assertThat(submissionRepo.countAllSubmissions(user)).isEqualTo(2)
    }

    @Test
    fun `what is left of a deleted submission is only whose it was and when`() {
        val user = newUser()
        val created = Instant.parse("2026-10-01T10:00:00Z")
        val id = submission(user, audio(user, "secret-name.m4a"), createdAt = created)

        delete(user, id.toString())

        val row = jdbcTemplate.queryForMap("SELECT * FROM deleted_submissions WHERE id = ?", id)
        assertThat(row.keys.map { it.lowercase() }).containsExactlyInAnyOrder("id", "user_id", "created_at", "deleted_at")
        assertThat((row["created_at"] as Timestamp).toInstant()).isEqualTo(created)
    }
}
