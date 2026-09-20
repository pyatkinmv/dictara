package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioStorage
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
import java.util.UUID

/**
 * The Speaker Picker: `PUT /transcriptions/{id}/speakers`, and how `GET /transcriptions/{id}` shows the result.
 *
 * The transcript used in most tests has three voices in seven phrases:
 * ```
 * idx  0 1 2 3 4 5 6
 * voice A B C A B B C     (A = SPEAKER_00, B = SPEAKER_01, C = SPEAKER_02)
 * ```
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SpeakerAssignmentIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private lateinit var submission: UUID
    private lateinit var transcript: UUID

    @BeforeEach
    fun setUp() {
        userId = newUser()
        val t = addTranscript(userId, "A B C A B B C")
        submission = t.first
        transcript = t.second
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    /** A finished transcription whose phrases have these voices (letters A, B, C, '-' for none); returns (submission, transcript). */
    private fun addTranscript(owner: UUID, voices: String, withTranscript: Boolean = true): Pair<UUID, UUID> {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.mp3', 'audio/mp4', 1000, ?, ?)",
            audioId, owner, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, 'off', 'web', 'done')",
            submissionId, owner, audioId,
        )
        if (withTranscript) {
            val phrases = voices.split(" ").mapIndexed { i, v ->
                val voice = if (v == "-") "null" else "\"SPEAKER_0${v[0] - 'A'}\""
                """{"start": ${i * 10}, "end": ${i * 10 + 10}, "text": "p$i", "speaker": $voice}"""
            }.joinToString(prefix = "[", postfix = "]")
            insertTranscript(transcriptId, submissionId, phrases)
        }
        return submissionId to transcriptId
    }

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

    private fun speaker(name: String, owner: UUID = userId): UUID =
        UUID.fromString(call(HttpMethod.POST, "/speakers", """{"name": "$name"}""", owner).body!!["id"].asText())

    private fun assign(scope: String, indexes: List<Int>, speaker: UUID? = null, name: String? = null, tx: UUID = submission, user: UUID? = userId): Reply {
        val who = speaker?.let { """, "speaker_id": "$it"""" } ?: name?.let { """, "name": "$it"""" } ?: ""
        return call(HttpMethod.PUT, "/transcriptions/$tx/speakers", """{"scope": "$scope", "segment_indexes": $indexes$who}""", user)
    }

    private fun detail(tx: UUID = submission) = call(HttpMethod.GET, "/transcriptions/$tx").body!!

    /** Who the detail says speaks each phrase, the way a client draws it: own mark, else voice's speaker, else the voice label. */
    private fun shown(): List<String> {
        val d = detail()
        val byVoice = d["voices"]?.associate { it["voice"].asText() to it["speaker"]["name"].asText() } ?: emptyMap()
        return d["segments"].map { s ->
            s["assigned_speaker"]?.get("name")?.asText() ?: s["speaker"]?.asText()?.let { byVoice[it] ?: it } ?: "-"
        }
    }

    private fun voiceRows() = jdbcTemplate.queryForList("SELECT voice, speaker_id FROM transcript_voices WHERE transcript_id = ?", transcript)
    private fun markedPhrases(): List<Int> = jdbcTemplate.queryForList("SELECT idx FROM transcript_segments WHERE transcript_id = ? AND speaker_id IS NOT NULL ORDER BY idx", Int::class.java, transcript)

    // ── "All segments by Speaker N": the voice is settled once and for all ─────

    @Test
    fun `all by a voice makes the voice that speaker in this transcript, with one row and no marks on phrases`() {
        val maria = speaker("Maria")

        val reply = assign("all", listOf(1, 4, 5), maria) // everything voice B says

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!["name"].asText()).isEqualTo("Maria")
        assertThat(voiceRows()).hasSize(1)
        assertThat(voiceRows().single()["voice"]).isEqualTo("SPEAKER_01")
        assertThat(markedPhrases()).isEmpty()
        assertThat(shown()).containsExactly("SPEAKER_00", "Maria", "SPEAKER_02", "SPEAKER_00", "Maria", "Maria", "SPEAKER_02")
    }

    @Test
    fun `the detail lists the named voices, for the row of chips`() {
        val maria = speaker("Maria")
        assign("all", listOf(1, 4, 5), maria)

        val voices = detail()["voices"]

        assertThat(voices).hasSize(1)
        assertThat(voices[0]["voice"].asText()).isEqualTo("SPEAKER_01")
        assertThat(voices[0]["speaker"]["id"].asText()).isEqualTo(maria.toString())
        assertThat(voices[0]["speaker"]["name"].asText()).isEqualTo("Maria")
        assertThat(voices[0]["speaker"]["color"].asText()).matches("#[0-9A-F]{6}")
    }

    @Test
    fun `naming a voice again replaces the speaker, and drops marks the phrases of that voice had`() {
        val maria = speaker("Maria")
        val james = speaker("James")
        assign("segment", listOf(4), james) // one phrase of voice B is James's
        assertThat(markedPhrases()).containsExactly(4)

        assign("all", listOf(1, 4, 5), maria) // now everything voice B says: the mark on phrase 4 goes

        assertThat(markedPhrases()).isEmpty()
        assertThat(shown().slice(listOf(1, 4, 5))).containsOnly("Maria")
        assign("all", listOf(1, 4, 5), james)
        assertThat(voiceRows()).hasSize(1)
        assertThat(shown().slice(listOf(1, 4, 5))).containsOnly("James")
    }

    @Test
    fun `everything under one name that is made of a whole voice and some single phrases is settled in one go`() {
        val maria = speaker("Maria")
        val alexei = speaker("Alexei")
        assign("all", listOf(1, 4, 5), maria) // voice B is Maria
        assign("segment", listOf(2), maria) // and phrase 2 (voice C) is Maria's too
        assertThat(shown()).containsExactly("SPEAKER_00", "Maria", "Maria", "SPEAKER_00", "Maria", "Maria", "SPEAKER_02")

        // "All segments by Maria" (phrases 1, 2, 4, 5) become Alexei's.
        assign("all", listOf(1, 2, 4, 5), alexei)

        assertThat(shown()).containsExactly("SPEAKER_00", "Alexei", "Alexei", "SPEAKER_00", "Alexei", "Alexei", "SPEAKER_02")
        assertThat(voiceRows().single()["speaker_id"]).isEqualTo(alexei) // voice B moved as a whole
        assertThat(markedPhrases()).containsExactly(2) // the lone phrase carries its own mark
    }

    @Test
    fun `all by a phrase without a voice marks each phrase, there is no voice to settle`() {
        val (s, t) = addTranscript(userId, "- - A")
        val maria = speaker("Maria")

        assign("all", listOf(0, 1), maria, tx = s)

        assertThat(jdbcTemplate.queryForList("SELECT idx FROM transcript_segments WHERE transcript_id = ? AND speaker_id IS NOT NULL ORDER BY idx", Int::class.java, t)).containsExactly(0, 1)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcript_voices WHERE transcript_id = ?", Int::class.java, t)).isZero()
    }

    @Test
    fun `only some phrases of a voice are marked, not the whole voice`() {
        val maria = speaker("Maria")

        assign("all", listOf(1, 4), maria) // voice B has three phrases; these are two of them

        assertThat(voiceRows()).isEmpty()
        assertThat(markedPhrases()).containsExactly(1, 4)
        assertThat(shown()).containsExactly("SPEAKER_00", "Maria", "SPEAKER_02", "SPEAKER_00", "Maria", "SPEAKER_01", "SPEAKER_02")
    }

    // ── "This segment only" ────────────────────────────────────────────────────

    @Test
    fun `one phrase gets a mark of its own and the voice is left alone`() {
        val maria = speaker("Maria")

        assign("segment", listOf(1), maria)

        assertThat(markedPhrases()).containsExactly(1)
        assertThat(voiceRows()).isEmpty()
        assertThat(shown()).containsExactly("SPEAKER_00", "Maria", "SPEAKER_02", "SPEAKER_00", "SPEAKER_01", "SPEAKER_01", "SPEAKER_02")
        assertThat(detail()["segments"][1]["assigned_speaker"]["name"].asText()).isEqualTo("Maria")
        assertThat(detail()["segments"][1]["speaker"].asText()).describedAs("the diarizer's label stays").isEqualTo("SPEAKER_01")
        assertThat(detail()["segments"][0].has("assigned_speaker")).isFalse()
    }

    @Test
    fun `a phrase moved to the speaker its voice already is needs no mark, and an old mark is dropped`() {
        val maria = speaker("Maria")
        val james = speaker("James")
        assign("all", listOf(1, 4, 5), maria) // voice B is Maria
        assign("segment", listOf(4), james) // phrase 4 is James's
        assertThat(markedPhrases()).containsExactly(4)

        assign("segment", listOf(4), maria) // back to Maria: what the voice says

        assertThat(markedPhrases()).isEmpty()
        assertThat(shown()[4]).isEqualTo("Maria")
    }

    @Test
    fun `a phrase can be moved to a speaker that is already elsewhere in the transcript`() {
        val maria = speaker("Maria")
        assign("all", listOf(1, 4, 5), maria)

        assign("segment", listOf(0), maria) // phrase 0 (voice A) is Maria's too

        assertThat(markedPhrases()).containsExactly(0)
        assertThat(shown()).containsExactly("Maria", "Maria", "SPEAKER_02", "SPEAKER_00", "Maria", "Maria", "SPEAKER_02")
    }

    @Test
    fun `several phrases at once`() {
        val maria = speaker("Maria")

        assign("segment", listOf(0, 3, 6), maria) // 0 and 3 are all of voice A; 6 is one of voice C's two

        assertThat(voiceRows().map { it["voice"] }).containsExactly("SPEAKER_00")
        assertThat(markedPhrases()).containsExactly(6)
    }

    // ── Marking every phrase of a voice one by one is naming the voice ─────────

    @Test
    fun `marking all the phrases of a voice one by one settles the voice, like naming it at once`() {
        val maria = speaker("Maria")

        assign("segment", listOf(1), maria)
        assign("segment", listOf(4), maria)
        assertThat(voiceRows()).describedAs("two of three phrases: not yet").isEmpty()
        assign("segment", listOf(5), maria) // the last phrase of voice B

        assertThat(voiceRows().single()["voice"]).isEqualTo("SPEAKER_01")
        assertThat(markedPhrases()).isEmpty()
        assertThat(detail()["voices"].map { it["speaker"]["name"].asText() }).containsExactly("Maria")
        assertThat(shown()).containsExactly("SPEAKER_00", "Maria", "SPEAKER_02", "SPEAKER_00", "Maria", "Maria", "SPEAKER_02")
    }

    @Test
    fun `marking all the phrases of a named voice with somebody else gives the voice to them`() {
        val maria = speaker("Maria")
        val james = speaker("James")
        assign("all", listOf(1, 4, 5), maria)

        assign("segment", listOf(1, 4), james)
        assertThat(voiceRows().single()["speaker_id"]).describedAs("one phrase still Maria's").isEqualTo(maria)
        assign("segment", listOf(5), james)

        assertThat(voiceRows().single()["speaker_id"]).isEqualTo(james)
        assertThat(markedPhrases()).isEmpty()
    }

    @Test
    fun `a voice with a phrase of somebody else in it is left as it is`() {
        val maria = speaker("Maria")
        val james = speaker("James")

        assign("segment", listOf(1, 4), maria)
        assign("segment", listOf(5), james)

        assertThat(voiceRows()).isEmpty()
        assertThat(markedPhrases()).containsExactly(1, 4, 5)
    }

    @Test
    fun `the whole transcript given to one speaker makes every voice that speaker's`() {
        val maria = speaker("Maria")

        assign("segment", (0..6).toList(), maria)

        assertThat(voiceRows().map { it["voice"] }).containsExactlyInAnyOrder("SPEAKER_00", "SPEAKER_01", "SPEAKER_02")
        assertThat(markedPhrases()).isEmpty()
        assertThat(shown()).containsOnly("Maria")
    }

    // ── The speaker: from the library, or made by the name ────────────────────

    @Test
    fun `a name that is not in the library creates the speaker, one that is reuses them in any case`() {
        val created = assign("segment", listOf(0), name = "  New   Person ")
        assertThat(created.body!!["name"].asText()).isEqualTo("New Person")

        val reused = assign("segment", listOf(1), name = "new person")

        assertThat(reused.body!!["id"].asText()).isEqualTo(created.body["id"].asText())
        assertThat(call(HttpMethod.GET, "/speakers").body!!["speakers"]).hasSize(1)
    }

    @Test
    fun `the library shows the effect at once, in time and transcriptions`() {
        val maria = speaker("Maria")

        assign("all", listOf(1, 4, 5), maria) // three phrases of ten seconds each

        val entry = call(HttpMethod.GET, "/speakers").body!!["speakers"].single()
        assertThat(entry["transcription_count"].asInt()).isEqualTo(1)
        assertThat(entry["speaking_s"].asDouble()).isEqualTo(30.0)
    }

    @Test
    fun `the old jobs endpoint still shows the diarizer's labels only`() {
        val maria = speaker("Maria")
        assign("all", listOf(1, 4, 5), maria)
        assign("segment", listOf(0), maria)

        val segments = json.readTree(rest.getForObject("/jobs/$submission", String::class.java))["result"]["segments"]

        assertThat(segments.map { it["speaker"].asText() }).containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_02", "SPEAKER_00", "SPEAKER_01", "SPEAKER_01", "SPEAKER_02")
    }

    // ── Refusals ───────────────────────────────────────────────────────────────

    @Test
    fun `bad requests are 400 and change nothing`() {
        val maria = speaker("Maria")

        assertThat(assign("segment", listOf(7), maria).status).describedAs("no such phrase").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(assign("segment", listOf(-1), maria).status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(assign("segment", emptyList(), maria).status).describedAs("no phrases").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(assign("segment", listOf(0)).status).describedAs("no speaker").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(assign("everyone", listOf(0), maria).status).describedAs("unknown scope").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(assign("segment", listOf(0), name = "  ").status).describedAs("blank name").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(markedPhrases()).isEmpty()
        assertThat(voiceRows()).isEmpty()
    }

    @Test
    fun `someone else's transcription or speaker, a missing one and a malformed id are 404, without a token 401`() {
        val other = newUser()
        val theirs = addTranscript(other, "A B").first
        val theirSpeaker = speaker("Theirs", other)
        val mine = speaker("Mine")

        assertThat(assign("segment", listOf(0), mine, tx = theirs).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(assign("segment", listOf(0), theirSpeaker).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(assign("segment", listOf(0), mine, tx = UUID.randomUUID()).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(call(HttpMethod.PUT, "/transcriptions/nope/speakers", """{"scope": "segment", "segment_indexes": [0], "speaker_id": "$mine"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(assign("segment", listOf(0), mine, user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(markedPhrases()).isEmpty()
    }

    @Test
    fun `a transcription without a transcript yet is a 409`() {
        val pending = addTranscript(userId, "A", withTranscript = false).first
        val maria = speaker("Maria")

        assertThat(assign("segment", listOf(0), maria, tx = pending).status).isEqualTo(HttpStatus.CONFLICT)
    }

    // ── What happens to the assignments later ──────────────────────────────────

    @Test
    fun `renaming a speaker shows in the transcript, deleting them puts the voice back to its label`() {
        val maria = speaker("Maria")
        assign("all", listOf(1, 4, 5), maria)
        assign("segment", listOf(0), maria)

        call(HttpMethod.PUT, "/speakers/$maria", """{"name": "Masha"}""")
        assertThat(shown()).containsExactly("Masha", "Masha", "SPEAKER_02", "SPEAKER_00", "Masha", "Masha", "SPEAKER_02")

        call(HttpMethod.DELETE, "/speakers/$maria")
        assertThat(shown()).containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_02", "SPEAKER_00", "SPEAKER_01", "SPEAKER_01", "SPEAKER_02")
        assertThat(detail()["voices"]).isEmpty()
        assertThat(detail()["segments"]).hasSize(7) // no text is lost
    }

    @Test
    fun `assignments of one transcription do not touch another`() {
        val (otherSubmission, _) = addTranscript(userId, "A B C A B B C")
        val maria = speaker("Maria")

        assign("all", listOf(1, 4, 5), maria)

        assertThat(detail(otherSubmission)["voices"]).isEmpty()
    }
}
