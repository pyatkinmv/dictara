package com.dictara.gateway

import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.SpeakerGuess
import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.service.SuggestionService
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
 * What the AI proposes for a transcription: how the answer of the model is checked and kept
 * ([SuggestionService.record]), how it shows in `GET /transcriptions/{id}`, and settling it
 * (`POST /transcriptions/{id}/suggestions/...`).
 *
 * The transcript has three voices; two of them say who they are:
 * ```
 * 0 SPEAKER_00  Hello everyone, I'm Anna and I run this meeting.
 * 1 SPEAKER_01  Thanks Anna. My name is Boris Petrov, I do finance.
 * 2 SPEAKER_02  We discuss the budget today.
 * 3 SPEAKER_00  Boris, please start with the budget.
 * ```
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class SuggestionsIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var suggestionService: SuggestionService
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private lateinit var submission: UUID
    private lateinit var transcript: UUID

    private val anna = SpeakerGuess("SPEAKER_00", "Anna", "I'm Anna and I run this meeting")
    private val boris = SpeakerGuess("SPEAKER_01", "Boris Petrov", "My name is Boris Petrov")

    @BeforeEach
    fun setUp() {
        userId = newUser()
        val t = addTranscript(userId)
        submission = t.first
        transcript = t.second
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private fun addTranscript(owner: UUID, title: String? = null): Pair<UUID, UUID> {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.mp3', 'audio/mp4', 1000, ?, ?)",
            audioId, owner, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, 'auto', 'web', 'done')",
            submissionId, owner, audioId,
        )
        val lines = listOf(
            "SPEAKER_00" to "Hello everyone, I'm Anna and I run this meeting.",
            "SPEAKER_01" to "Thanks Anna. My name is Boris Petrov, I do finance.",
            "SPEAKER_02" to "We discuss the budget today.",
            "SPEAKER_00" to "Boris, please start with the budget.",
        )
        val phrases = lines.mapIndexed { i, (voice, text) ->
            """{"start": ${i * 10}, "end": ${i * 10 + 10}, "text": "$text", "speaker": "$voice"}"""
        }.joinToString(prefix = "[", postfix = "]")
        insertTranscript(transcriptId, submissionId, phrases, title = title)
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

    private fun post(path: String, body: String? = null, user: UUID? = userId) = call(HttpMethod.POST, path, body, user)

    private fun tag(label: String, owner: UUID = userId): UUID =
        UUID.fromString(post("/tags", """{"label": "$label"}""", owner).body!!["id"].asText())

    private fun speaker(name: String, owner: UUID = userId): UUID =
        UUID.fromString(post("/speakers", """{"name": "$name"}""", owner).body!!["id"].asText())

    private fun detail(tx: UUID = submission) = call(HttpMethod.GET, "/transcriptions/$tx").body!!
    private fun suggestions(tx: UUID = submission): JsonNode? = detail(tx)["suggestions"]
    private fun at(path: String) = "/transcriptions/$submission/suggestions/$path"

    private fun rows(status: String? = null): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM transcript_suggestions WHERE transcript_id = ?" + (status?.let { " AND status = '$it'" } ?: ""),
        Int::class.java, transcript,
    )!!

    private fun libraryNames(): List<String> = call(HttpMethod.GET, "/speakers").body!!["speakers"].map { it["name"].asText() }

    private fun record(analysis: Analysis) = suggestionService.record(submission, analysis)

    // ── What is kept of the model's answer ─────────────────────────────────────

    @Test
    fun `valid proposals are kept and shown in the detail, and the speaker is not in the library yet`() {
        tag("budget")
        record(Analysis("s", title = "Budget meeting", tags = listOf("budget"), speakers = listOf(anna, boris)))

        val s = suggestions()!!

        assertThat(s["title"].asText()).isEqualTo("Budget meeting")
        assertThat(s["tags"].map { it["label"].asText() }).containsExactly("budget")
        assertThat(s["tags"][0]["color"].asText()).matches("#[0-9A-F]{6}")
        assertThat(s["speakers"].map { it["voice"].asText() }).containsExactly("SPEAKER_00", "SPEAKER_01")
        assertThat(s["speakers"].map { it["name"].asText() }).containsExactly("Anna", "Boris Petrov")
        assertThat(s["speakers"][0]["color"].asText()).matches("#[0-9A-F]{6}")
        assertThat(s["speakers"][0]["evidence"].asText()).isEqualTo("I'm Anna and I run this meeting")
        assertThat(s["speakers"][0].has("speaker_id")).isFalse() // a new name: nobody in the library yet
        assertThat(libraryNames()).isEmpty()
        assertThat(s.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("title", "tags", "speakers")
    }

    @Test
    fun `a transcription without proposals has no suggestions field`() {
        assertThat(detail().has("suggestions")).isFalse()
    }

    @Test
    fun `a name of the library is proposed as that speaker even when the transcript does not say it`() {
        val maria = speaker("Maria Chen")

        record(Analysis("s", speakers = listOf(SpeakerGuess("SPEAKER_02", "maria chen", "We discuss the budget today"))))

        val s = suggestions()!!["speakers"].single()
        assertThat(s["name"].asText()).isEqualTo("Maria Chen") // as the library spells it
        assertThat(s["speaker_id"].asText()).isEqualTo(maria.toString())
    }

    @Test
    fun `a quote copied with the timestamp and the voice label of its line is still found, and kept without them`() {
        record(Analysis("s", speakers = listOf(
            SpeakerGuess("SPEAKER_00", "Anna", "[0:00:00 --> 0:00:10] [SPEAKER_00] Hello everyone, I'm Anna and I run this meeting."),
            SpeakerGuess("SPEAKER_01", "Boris Petrov", "[SPEAKER_01] My name is Boris Petrov"),
        )))

        val s = suggestions()!!["speakers"]
        assertThat(s.map { it["voice"].asText() }).containsExactly("SPEAKER_00", "SPEAKER_01")
        assertThat(s[0]["evidence"].asText()).isEqualTo("Hello everyone, I'm Anna and I run this meeting.")
        assertThat(s[1]["evidence"].asText()).isEqualTo("My name is Boris Petrov")
    }

    @Test
    fun `a quote that is missing, only markers, or not in the transcript does not stop a proposal, and is not kept`() {
        record(Analysis("s", speakers = listOf(
            SpeakerGuess("SPEAKER_00", "Anna", "[0:00:00 --> 0:00:10] [SPEAKER_00]"),
            SpeakerGuess("SPEAKER_01", "Boris Petrov", "[SPEAKER_01] I am Boris and I sing opera"),
            SpeakerGuess("SPEAKER_02", "Anna", null),
        )))

        val s = suggestions()!!["speakers"]
        assertThat(s.map { it["voice"].asText() }).containsExactly("SPEAKER_00", "SPEAKER_01") // one name per voice and per name
        assertThat(s.any { it.has("evidence") }).isFalse() // nothing is kept that is not in the transcript
    }

    @Test
    fun `proposals that do not hold up are dropped`() {
        tag("budget")
        val analysis = Analysis(
            "s",
            title = " ",
            tags = listOf("budget", "invented", "not a tag!"), // the library has only 'budget'
            speakers = listOf(
                SpeakerGuess("SPEAKER_09", "Anna", "I'm Anna"),                    // no such voice
                SpeakerGuess("SPEAKER_02", "Carla", "We discuss the budget today"), // new name that is not in the transcript
                SpeakerGuess("SPEAKER_02", "  ", "We discuss the budget today"),    // not a name
                boris.copy(name = "Bor"),                                            // "Bor" is only part of a word... of "Boris"
            ),
        )

        record(analysis)

        val s = suggestions()!!
        assertThat(s.has("title")).isFalse()
        assertThat(s["tags"].map { it["label"].asText() }).containsExactly("budget")
        assertThat(s["speakers"].size()).isZero()
        assertThat(rows()).isEqualTo(1) // just the tag
    }

    @Test
    fun `a name has to be a whole word of the transcript, not a part of one`() {
        record(Analysis("s", speakers = listOf(SpeakerGuess("SPEAKER_00", "Ann", "I'm Anna and I run this meeting"))))

        assertThat(suggestions()).isNull()
    }

    @Test
    fun `one proposal per voice and per name, and at most five tags`() {
        for (label in listOf("a", "b", "c", "d", "e", "f", "g")) tag(label)

        record(
            Analysis(
                "s",
                tags = listOf("a", "b", "c", "d", "e", "f", "g", "A"),
                speakers = listOf(anna, anna.copy(name = "Boris Petrov"), boris.copy(voice = "SPEAKER_00"), boris),
            ),
        )

        val s = suggestions()!!
        assertThat(s["tags"].map { it["label"].asText() }).containsExactly("a", "b", "c", "d", "e")
        // Anna took SPEAKER_00; "Boris Petrov" for SPEAKER_00 is a second proposal for that voice; then Boris on his own voice.
        assertThat(s["speakers"].map { it["voice"].asText() to it["name"].asText() })
            .containsExactly("SPEAKER_00" to "Anna", "SPEAKER_01" to "Boris Petrov")
    }

    @Test
    fun `a title equal to the current one is not proposed`() {
        val (sub, tr) = addTranscript(userId, title = "Budget meeting")

        suggestionService.record(sub, Analysis("s", title = "budget meeting"))

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transcript_suggestions WHERE transcript_id = ?", Int::class.java, tr)).isZero()
    }

    @Test
    fun `quotes around the title are removed`() {
        record(Analysis("s", title = "\"Budget meeting\""))

        assertThat(suggestions()!!["title"].asText()).isEqualTo("Budget meeting")
    }

    // ── Accepting and rejecting one by one ────────────────────────────────────

    @Test
    fun `accepting a new speaker creates them in the library and makes the voice that speaker`() {
        record(Analysis("s", speakers = listOf(anna, boris)))

        val reply = post(at("speakers/SPEAKER_00/accept"))

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(libraryNames()).containsExactly("Anna") // Boris is still only proposed
        val d = detail()
        assertThat(d["voices"].map { it["voice"].asText() to it["speaker"]["name"].asText() }).containsExactly("SPEAKER_00" to "Anna")
        assertThat(d["suggestions"]["speakers"].map { it["voice"].asText() }).containsExactly("SPEAKER_01")
        assertThat(reply.body!!["suggestions"]["speakers"].map { it["voice"].asText() }).containsExactly("SPEAKER_01")
    }

    @Test
    fun `accepting a proposal for a speaker of the library uses that speaker and adds nobody`() {
        val maria = speaker("Maria Chen")
        record(Analysis("s", speakers = listOf(SpeakerGuess("SPEAKER_02", "Maria Chen", "We discuss the budget today"))))

        post(at("speakers/SPEAKER_02/accept"))

        assertThat(libraryNames()).containsExactly("Maria Chen")
        assertThat(detail()["voices"].single()["speaker"]["id"].asText()).isEqualTo(maria.toString())
    }

    @Test
    fun `rejecting a speaker leaves the library and the transcript as they were`() {
        record(Analysis("s", speakers = listOf(anna)))

        val reply = post(at("speakers/SPEAKER_00/reject"))

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!.has("suggestions")).isFalse()
        assertThat(libraryNames()).isEmpty()
        assertThat(detail()["voices"].size()).isZero() // no voice is named
        assertThat(detail().has("suggestions")).isFalse()
        assertThat(rows("rejected")).isEqualTo(1)
    }

    @Test
    fun `a rejected proposal is not proposed again`() {
        record(Analysis("s", speakers = listOf(anna)))
        post(at("speakers/SPEAKER_00/reject"))

        record(Analysis("s", speakers = listOf(anna)))

        assertThat(suggestions()).isNull()
    }

    @Test
    fun `a voice the user has named meanwhile is no longer proposed`() {
        record(Analysis("s", speakers = listOf(anna, boris)))
        val own = speaker("Anya")
        call(HttpMethod.PUT, "/transcriptions/$submission/speakers", """{"scope": "all", "segment_indexes": [0, 3], "speaker_id": "$own"}""")

        assertThat(suggestions()!!["speakers"].map { it["voice"].asText() }).containsExactly("SPEAKER_01")
        assertThat(post(at("speakers/SPEAKER_00/accept")).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(libraryNames()).containsExactly("Anya")
    }

    @Test
    fun `accepting a tag puts it on the transcription, rejecting one does not`() {
        tag("budget")
        tag("finance")
        record(Analysis("s", tags = listOf("budget", "finance")))

        assertThat(post(at("tags/budget/accept")).status).isEqualTo(HttpStatus.OK)
        assertThat(post(at("tags/finance/reject")).status).isEqualTo(HttpStatus.OK)

        assertThat(detail()["tags"].map { it["label"].asText() }).containsExactly("budget")
        assertThat(detail().has("suggestions")).isFalse()
        assertThat(call(HttpMethod.GET, "/tags").body!!["tags"].map { it["label"].asText() }).containsExactlyInAnyOrder("budget", "finance")
    }

    @Test
    fun `a tag that is already on the transcription is not proposed`() {
        tag("budget")
        record(Analysis("s", tags = listOf("budget")))

        post("/transcriptions/$submission/tags", """{"label": "budget"}""")

        assertThat(suggestions()).isNull()
    }

    @Test
    fun `settling a proposal that is not pending is a 404`() {
        tag("budget")
        record(Analysis("s", tags = listOf("budget"), speakers = listOf(anna)))
        post(at("tags/budget/reject"))
        post(at("speakers/SPEAKER_00/accept"))

        assertThat(post(at("tags/budget/accept")).status).isEqualTo(HttpStatus.NOT_FOUND) // already rejected
        assertThat(post(at("tags/budget/reject")).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(at("tags/unknown/accept")).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(at("speakers/SPEAKER_00/accept")).status).isEqualTo(HttpStatus.NOT_FOUND) // already accepted
        assertThat(post(at("speakers/SPEAKER_07/reject")).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(detail()["tags"].size()).isZero() // the rejected tag did not come back through the 404 calls
    }

    // ── Everything at once ────────────────────────────────────────────────────

    @Test
    fun `accept all renames, tags and names the voices, and nothing is left`() {
        tag("budget")
        record(Analysis("s", title = "Budget meeting", tags = listOf("budget"), speakers = listOf(anna, boris)))

        val reply = post(at("accept-all"))

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!.has("suggestions")).isFalse()
        val d = detail()
        assertThat(d["title"].asText()).isEqualTo("Budget meeting")
        assertThat(d["tags"].map { it["label"].asText() }).containsExactly("budget")
        assertThat(d["voices"].map { it["speaker"]["name"].asText() }).containsExactly("Anna", "Boris Petrov")
        assertThat(d.has("suggestions")).isFalse()
        assertThat(libraryNames()).containsExactlyInAnyOrder("Anna", "Boris Petrov")
        assertThat(rows("pending")).isZero()
    }

    @Test
    fun `dismiss all changes nothing on the transcription and creates nothing`() {
        tag("budget")
        record(Analysis("s", title = "Budget meeting", tags = listOf("budget"), speakers = listOf(anna, boris)))
        val before = detail()["title"].asText()

        val reply = post(at("dismiss-all"))

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!.has("suggestions")).isFalse()
        val d = detail()
        assertThat(d["title"].asText()).isEqualTo(before)
        assertThat(d["tags"].size()).isZero()
        assertThat(d["voices"].size()).isZero()
        assertThat(libraryNames()).isEmpty()
        assertThat(rows("rejected")).isEqualTo(4)
    }

    @Test
    fun `accept all after some were settled takes only what is left`() {
        tag("budget")
        record(Analysis("s", tags = listOf("budget"), speakers = listOf(anna, boris)))
        post(at("speakers/SPEAKER_00/reject"))

        post(at("accept-all"))

        assertThat(libraryNames()).containsExactly("Boris Petrov")
        assertThat(detail()["tags"].map { it["label"].asText() }).containsExactly("budget")
    }

    // ── Who can settle them, and what removes them ────────────────────────────

    @Test
    fun `someone else's transcription is not found and an anonymous call is refused`() {
        record(Analysis("s", speakers = listOf(anna)))
        val stranger = newUser()

        assertThat(post(at("accept-all"), user = stranger).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(at("dismiss-all"), user = stranger).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(at("speakers/SPEAKER_00/accept"), user = stranger).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post(at("accept-all"), user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(post("/transcriptions/not-a-uuid/suggestions/accept-all").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(suggestions()!!["speakers"].size()).isEqualTo(1)
    }

    @Test
    fun `deleting a tag or a speaker of the library removes the proposals that point at it`() {
        val budget = tag("budget")
        val maria = speaker("Maria Chen")
        record(Analysis("s", tags = listOf("budget"), speakers = listOf(SpeakerGuess("SPEAKER_02", "Maria Chen", "We discuss the budget today"), anna)))
        assertThat(rows()).isEqualTo(3)

        call(HttpMethod.DELETE, "/tags/$budget")
        call(HttpMethod.DELETE, "/speakers/$maria")

        assertThat(rows()).isEqualTo(1) // only Anna: a name, not a library speaker
        assertThat(suggestions()!!["speakers"].map { it["name"].asText() }).containsExactly("Anna")
    }

    @Test
    fun `deleting the transcription removes its proposals`() {
        record(Analysis("s", title = "Budget meeting", speakers = listOf(anna)))

        jdbcTemplate.update("DELETE FROM transcripts WHERE id = ?", transcript)

        assertThat(rows()).isZero()
    }

    @Test
    fun `a speaker who was renamed meanwhile is proposed under the new name`() {
        val maria = speaker("Maria Chen")
        record(Analysis("s", speakers = listOf(SpeakerGuess("SPEAKER_02", "Maria Chen", "We discuss the budget today"))))

        call(HttpMethod.PUT, "/speakers/$maria", """{"name": "Maria Rossi"}""")

        assertThat(suggestions()!!["speakers"].single()["name"].asText()).isEqualTo("Maria Rossi")
    }
}
