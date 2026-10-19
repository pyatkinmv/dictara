package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.util.NameColors
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID

/** The speaker library (`/speakers`): names, usage counted from voices and phrases, editing, deleting. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SpeakerLibraryIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private var minute = 0L

    @BeforeEach
    fun createUser() {
        userId = newUser()
        minute = 0
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private class Reply(val status: HttpStatus, val body: JsonNode?)

    private fun call(method: HttpMethod, path: String, body: String? = null, user: UUID? = userId): Reply {
        val headers = HttpHeaders().apply {
            user?.let { setBearerAuth(jwt.issue(it)) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(HttpStatus.valueOf(response.statusCode.value()), text?.let(json::readTree))
    }

    private fun get(path: String, user: UUID? = userId) = call(HttpMethod.GET, path, user = user)
    private fun post(path: String, body: String, user: UUID? = userId) = call(HttpMethod.POST, path, body, user)
    private fun put(path: String, body: String, user: UUID? = userId) = call(HttpMethod.PUT, path, body, user)
    private fun delete(path: String, user: UUID? = userId) = call(HttpMethod.DELETE, path, user = user)

    private fun library(user: UUID = userId) = get("/speakers", user).body!!["speakers"]
    private fun names(user: UUID = userId) = library(user).map { it["name"].asText() }
    private fun entry(name: String) = library().first { it["name"].asText() == name }

    private fun createSpeaker(name: String, description: String? = null, user: UUID = userId): UUID {
        val body = if (description == null) """{"name": "$name"}""" else """{"name": "$name", "description": "$description"}"""
        return UUID.fromString(post("/speakers", body, user).body!!["id"].asText())
    }

    /** A finished transcription with these phrases (`start end voice`); returns the transcript's id. */
    private fun addTranscript(name: String, owner: UUID = userId, title: String? = null, vararg phrases: Triple<Int, Int, String>): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        val createdAt = java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z").plusSeconds(60 * minute++))
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?)",
            audioId, owner, name, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', true, 'off', 'web', 'done', ?)",
            submissionId, owner, audioId, createdAt,
        )
        val segments = phrases.joinToString(prefix = "[", postfix = "]") { (s, e, v) -> """{"start": $s, "end": $e, "text": "t", "speaker": "$v"}""" }
        insertTranscript(transcriptId, submissionId, segments, title = title)
        return transcriptId
    }

    /** [voice] of the transcript is [speaker]. */
    private fun voice(transcript: UUID, voice: String, speaker: UUID) {
        jdbcTemplate.update("INSERT INTO transcript_voices(transcript_id, voice, speaker_id) VALUES (?, ?, ?)", transcript, voice, speaker)
    }

    /** Phrase number [idx] of the transcript is said by [speaker], whatever its voice. */
    private fun phraseBy(transcript: UUID, idx: Int, speaker: UUID) {
        jdbcTemplate.update("UPDATE transcript_segments SET speaker_id = ? WHERE transcript_id = ? AND idx = ?", speaker, transcript, idx)
    }

    // ── Creating ───────────────────────────────────────────────────────────────

    @Test
    fun `a new speaker is created with its color and shows up in the library`() {
        val reply = post("/speakers", """{"name": "Maria Chen", "description": "Head of Engineering"}""")

        assertThat(reply.status).isEqualTo(HttpStatus.CREATED)
        assertThat(reply.body!!["name"].asText()).isEqualTo("Maria Chen")
        assertThat(reply.body["color"].asText()).isEqualTo(NameColors.of("Maria Chen"))
        val entry = library().single()
        assertThat(entry["id"].asText()).isEqualTo(reply.body["id"].asText())
        assertThat(entry["description"].asText()).isEqualTo("Head of Engineering")
        assertThat(entry["transcription_count"].asInt()).isZero()
        assertThat(entry["speaking_s"].asDouble()).isZero()
        assertThat(entry["sample_titles"]).isEmpty()
    }

    @Test
    fun `the case is kept, the spaces are tidied, and any script or punctuation is fine`() {
        assertThat(post("/speakers", """{"name": "  Maria    Chen "}""").body!!["name"].asText()).isEqualTo("Maria Chen")
        assertThat(post("/speakers", """{"name": "Dr. O'Neil-Smith"}""").status).isEqualTo(HttpStatus.CREATED)
        assertThat(post("/speakers", """{"name": "Иван Петров"}""").body!!["name"].asText()).isEqualTo("Иван Петров")
        assertThat(post("/speakers", """{"name": "会议 主持人"}""").status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `an empty, blank, too long or control-character name is refused`() {
        for (bad in listOf("", "   ", "x".repeat(65), "bell\\u0007", "tab\\u0000")) {
            assertThat(post("/speakers", """{"name": "$bad"}""").status).describedAs("'$bad'").isEqualTo(HttpStatus.BAD_REQUEST)
        }
        assertThat(post("/speakers", "{}").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/speakers", """{"name": "${"x".repeat(64)}"}""").status).isEqualTo(HttpStatus.CREATED)
        assertThat(names()).hasSize(1)
    }

    @Test
    fun `a name that is taken, in any case, is a 409 - but only for the same user`() {
        createSpeaker("Maria Chen")

        assertThat(post("/speakers", """{"name": "Maria Chen"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(post("/speakers", """{"name": "maria chen"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(post("/speakers", """{"name": " MARIA   CHEN "}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(post("/speakers", """{"name": "Maria Chen"}""", user = newUser()).status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `the database itself refuses a duplicate in another case`() {
        createSpeaker("Maria Chen")

        val failure = runCatching {
            jdbcTemplate.update("INSERT INTO speakers(user_id, name) VALUES (?, 'MARIA CHEN')", userId)
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(names()).containsExactly("Maria Chen")
    }

    @Test
    fun `a description is trimmed, and a very long one is refused`() {
        createSpeaker("A", "  needs trimming  ")
        assertThat(library().single()["description"].asText()).isEqualTo("needs trimming")

        assertThat(post("/speakers", """{"name": "B", "description": "${"d".repeat(501)}"}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/speakers", """{"name": "C", "description": "${"d".repeat(500)}"}""").status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `view=brief has only who the speakers are, none of where they are heard`() {
        val alexei = createSpeaker("Alexei", "Host")
        createSpeaker("Maria")
        createSpeaker("Theirs", user = newUser())
        val t = addTranscript("a.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00")))
        voice(t, "SPEAKER_00", alexei)

        val reply = get("/speakers?view=brief")

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!.fieldNames().asSequence().toList()).containsExactly("speakers")
        val speakers = reply.body["speakers"]
        assertThat(speakers.map { it["name"].asText() }).containsExactly("Alexei", "Maria") // creation order, own only
        assertThat(speakers.first().fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "name", "color", "description")
        assertThat(speakers.first()["id"].asText()).isEqualTo(alexei.toString())
        assertThat(speakers.first()["color"].asText()).isEqualTo(NameColors.of("Alexei"))
        assertThat(speakers.first()["description"].asText()).isEqualTo("Host")
        assertThat(speakers[1].has("description")).isFalse() // none given: left out, as in the full view
    }

    @Test
    fun `view=full is the same as no view, and an unknown view is refused`() {
        createSpeaker("Alexei")

        assertThat(get("/speakers?view=full").body).isEqualTo(get("/speakers").body)
        assertThat(get("/speakers").body!!["speakers"].first().has("transcription_count")).isTrue()

        val bad = get("/speakers?view=tiny")
        assertThat(bad.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(bad.body!!["message"].asText()).contains("Unknown view 'tiny'").contains("full").contains("brief")
        assertThat(get("/speakers?view=BRIEF").status).isEqualTo(HttpStatus.BAD_REQUEST) // names are exact
    }

    @Test
    fun `the brief view needs a token too`() {
        assertThat(get("/speakers?view=brief", user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `the library needs a token and lists only the user's own speakers, oldest first`() {
        createSpeaker("First")
        createSpeaker("Second")
        createSpeaker("Theirs", user = newUser())

        assertThat(names()).containsExactly("First", "Second")
        assertThat(get("/speakers", user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(post("/speakers", """{"name": "a"}""", user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── Usage ──────────────────────────────────────────────────────────────────

    private fun submissionOf(transcript: UUID): UUID =
        jdbcTemplate.queryForObject("SELECT submission_id FROM transcripts WHERE id = ?", UUID::class.java, transcript)!!

    @Test
    fun `a transcription that is not finished is counted but is not offered as a sample to open`() {
        val alexei = createSpeaker("Alexei")
        val done = addTranscript("done.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00")))
        voice(done, "SPEAKER_00", alexei)
        val running = addTranscript("running.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00"))) // the newest
        voice(running, "SPEAKER_00", alexei)
        jdbcTemplate.update("UPDATE submissions SET status = 'summarizing' WHERE id = ?", submissionOf(running))

        assertThat(entry("Alexei")["transcription_count"].asInt()).isEqualTo(2)
        assertThat(entry("Alexei")["samples"].map { it["job_id"].asText() }).containsExactly(submissionOf(done).toString())
        assertThat(entry("Alexei")["sample_titles"].map { it.asText() }).containsExactly("done.mp3")
    }

    @Test
    fun `usage counts the transcriptions a speaker is in and the time of their phrases`() {
        val alexei = createSpeaker("Alexei")
        val maria = createSpeaker("Maria")
        createSpeaker("Nobody")
        // Transcript A: Alexei says 0-10 and 25-30, Maria 10-25.
        val a = addTranscript("a.mp3", title = "Meeting A", phrases = arrayOf(Triple(0, 10, "SPEAKER_00"), Triple(10, 25, "SPEAKER_01"), Triple(25, 30, "SPEAKER_00")))
        voice(a, "SPEAKER_00", alexei)
        voice(a, "SPEAKER_01", maria)
        // Transcript B (later): only Alexei, 0-20.
        val b = addTranscript("b.mp3", phrases = arrayOf(Triple(0, 20, "SPEAKER_00")))
        voice(b, "SPEAKER_00", alexei)

        assertThat(entry("Alexei")["transcription_count"].asInt()).isEqualTo(2)
        assertThat(entry("Alexei")["speaking_s"].asDouble()).isEqualTo(35.0) // 10 + 5 + 20
        assertThat(entry("Alexei")["sample_titles"].map { it.asText() }).containsExactly("b.mp3", "Meeting A") // newest first, file name without a title
        // The same two with the id that opens each (job_id = the submission of the transcript), for the card's links.
        assertThat(entry("Alexei")["samples"].map { it["job_id"].asText() to it["title"].asText() })
            .containsExactly(submissionOf(b).toString() to "b.mp3", submissionOf(a).toString() to "Meeting A")
        assertThat(entry("Nobody")["samples"]).isEmpty()
        assertThat(entry("Maria")["transcription_count"].asInt()).isEqualTo(1)
        assertThat(entry("Maria")["speaking_s"].asDouble()).isEqualTo(15.0)
        assertThat(entry("Nobody")["transcription_count"].asInt()).isZero()
        assertThat(entry("Nobody")["speaking_s"].asDouble()).isZero()
    }

    @Test
    fun `a phrase set to a speaker by hand is theirs, whatever its voice says`() {
        val alexei = createSpeaker("Alexei")
        val maria = createSpeaker("Maria")
        val t = addTranscript("t.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00"), Triple(10, 25, "SPEAKER_01")))
        voice(t, "SPEAKER_00", alexei)
        voice(t, "SPEAKER_01", maria)

        phraseBy(t, 1, alexei) // the 15 seconds of voice 1 are Alexei's after all

        assertThat(entry("Alexei")["speaking_s"].asDouble()).isEqualTo(25.0)
        assertThat(entry("Maria")["speaking_s"].asDouble()).isZero()
        assertThat(entry("Maria")["transcription_count"].asInt()).describedAs("still the speaker of a voice there").isEqualTo(1)
    }

    @Test
    fun `a speaker set only on a phrase is in that transcription, once however many phrases`() {
        val james = createSpeaker("James")
        val t = addTranscript("t.mp3", phrases = arrayOf(Triple(0, 4, "SPEAKER_00"), Triple(4, 6, "SPEAKER_00"), Triple(6, 9, "SPEAKER_01")))

        phraseBy(t, 0, james)
        phraseBy(t, 2, james)

        assertThat(entry("James")["transcription_count"].asInt()).isEqualTo(1)
        assertThat(entry("James")["speaking_s"].asDouble()).isEqualTo(7.0) // 4 + 3
    }

    @Test
    fun `other users' transcriptions do not count`() {
        val other = newUser()
        val mine = createSpeaker("Maria")
        val theirs = createSpeaker("Maria", user = other)
        val t = addTranscript("theirs.mp3", owner = other, phrases = arrayOf(Triple(0, 10, "SPEAKER_00")))
        voice(t, "SPEAKER_00", theirs)

        assertThat(entry("Maria")["transcription_count"].asInt()).isZero()
        assertThat(library(other).single()["transcription_count"].asInt()).isEqualTo(1)
        assertThat(mine).isNotEqualTo(theirs)
    }

    // ── Editing ────────────────────────────────────────────────────────────────

    @Test
    fun `a speaker can be renamed and re-described, together or one at a time`() {
        val id = createSpeaker("Maria Chen", "old text")

        assertThat(put("/speakers/$id", """{"name": "  Masha   Chen ", "description": "new text"}""").body!!["name"].asText()).isEqualTo("Masha Chen")
        assertThat(entry("Masha Chen")["description"].asText()).isEqualTo("new text")

        put("/speakers/$id", """{"description": "only text"}""")
        assertThat(library().single().let { it["name"].asText() to it["description"].asText() }).isEqualTo("Masha Chen" to "only text")

        put("/speakers/$id", """{"name": "Maria"}""")
        assertThat(library().single().let { it["name"].asText() to it["description"].asText() }).isEqualTo("Maria" to "only text")

        put("/speakers/$id", """{"description": ""}""")
        assertThat(library().single().has("description")).isFalse()
    }

    @Test
    fun `renaming keeps the speaker in their transcriptions`() {
        val id = createSpeaker("Maria")
        val t = addTranscript("t.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00")))
        voice(t, "SPEAKER_00", id)

        put("/speakers/$id", """{"name": "Masha"}""")

        assertThat(entry("Masha")["transcription_count"].asInt()).isEqualTo(1)
        assertThat(entry("Masha")["speaking_s"].asDouble()).isEqualTo(10.0)
    }

    @Test
    fun `renaming to a taken name is a 409, to its own name in another case is fine`() {
        createSpeaker("First")
        val second = createSpeaker("Second")

        assertThat(put("/speakers/$second", """{"name": "FIRST"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(put("/speakers/$second", """{"name": "SECOND"}""").status).isEqualTo(HttpStatus.OK)
        assertThat(put("/speakers/$second", """{"name": ""}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(names()).containsExactly("First", "SECOND")
    }

    @Test
    fun `someone else's speaker, a missing one and a malformed id are all 404`() {
        val theirs = createSpeaker("Theirs", user = newUser())

        assertThat(put("/speakers/$theirs", """{"name": "mine"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/speakers/$theirs").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(put("/speakers/${UUID.randomUUID()}", """{"name": "x"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/speakers/nope").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(names()).isEmpty()
    }

    // ── Deleting ───────────────────────────────────────────────────────────────

    @Test
    fun `deleting a speaker says how many transcriptions they were in and leaves the text alone`() {
        val maria = createSpeaker("Maria")
        val keep = createSpeaker("Keep")
        val a = addTranscript("a.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00"), Triple(10, 20, "SPEAKER_01")))
        val b = addTranscript("b.mp3", phrases = arrayOf(Triple(0, 10, "SPEAKER_00")))
        voice(a, "SPEAKER_00", maria)
        voice(a, "SPEAKER_01", keep)
        phraseBy(b, 0, maria) // in B only through one phrase

        val reply = delete("/speakers/$maria")

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!["affected_transcriptions"].asInt()).isEqualTo(2)
        assertThat(names()).containsExactly("Keep")
        // Nothing of the transcripts is lost: the phrases stay, the voice is just nobody's now.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcript_segments", Int::class.java)).isEqualTo(3)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcript_segments WHERE speaker_id IS NOT NULL", Int::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcript_voices", Int::class.java)).isEqualTo(1) // Keep's
        assertThat(entry("Keep")["transcription_count"].asInt()).isEqualTo(1)
    }

    @Test
    fun `deleting an unused speaker affects nothing`() {
        val id = createSpeaker("Unused")

        assertThat(delete("/speakers/$id").body!!["affected_transcriptions"].asInt()).isZero()
    }
}
