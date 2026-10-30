package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.util.UUID

/** `PUT /transcriptions/{id}/segments/{index}`: correcting the text of one phrase, and nothing else about it. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptTextEditIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()

    private fun newUser(): UUID = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private class Reply(val status: Int, val body: JsonNode?)

    private fun send(method: HttpMethod, path: String, bearer: String?, body: String? = null): Reply {
        val headers = HttpHeaders().apply {
            bearer?.let { setBearerAuth(it) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
        return Reply(response.statusCode.value(), response.body?.takeIf { it.isNotBlank() }?.let(json::readTree))
    }

    private fun edit(user: UUID?, id: Any, index: Any, text: String?): Reply {
        val body = if (text == null) "{}" else json.writeValueAsString(mapOf("text" to text))
        return send(HttpMethod.PUT, "/transcriptions/$id/segments/$index", user?.let { jwt.issue(it) }, body)
    }

    private fun detail(user: UUID, id: UUID): JsonNode = send(HttpMethod.GET, "/transcriptions/$id", jwt.issue(user)).body!!

    /** A finished transcription of [owner] with three phrases of two voices; returns its id. */
    private fun transcription(owner: UUID): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, 'call.m4a', 'audio/mp4', 1000, ?, ?, 30)",
            audioId, owner, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, 'auto', 'web', 'done')",
            submissionId, owner, audioId,
        )
        insertTranscript(
            UUID.randomUUID(), submissionId,
            """[{"start":0.0,"end":5.0,"text":"First phrase.","speaker":"SPEAKER_00"},
                {"start":5.0,"end":10.0,"text":"Send it by Fryday.","speaker":"SPEAKER_01"},
                {"start":10.0,"end":15.0,"text":"Third phrase.","speaker":"SPEAKER_00"}]""",
            title = "Call",
        )
        jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, 'The summary')", submissionId)
        return submissionId
    }

    private fun texts(user: UUID, id: UUID): List<String> = detail(user, id)["segments"].map { it["text"].asText() }

    @Test
    fun `a phrase gets the new text, the others and the rest of the record stay as they were`() {
        val user = newUser()
        val id = transcription(user)
        val before = detail(user, id)

        val reply = edit(user, id, 1, "Send it by Friday.")

        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.body!!["text"].asText()).isEqualTo("Send it by Friday.")
        val after = detail(user, id)
        assertThat(texts(user, id)).containsExactly("First phrase.", "Send it by Friday.", "Third phrase.")
        // time and speaker of the phrase, the summary, the title: untouched
        assertThat(after["segments"][1]["start"]).isEqualTo(before["segments"][1]["start"])
        assertThat(after["segments"][1]["end"]).isEqualTo(before["segments"][1]["end"])
        assertThat(after["segments"][1]["speaker"]).isEqualTo(before["segments"][1]["speaker"])
        assertThat(after["summary"]).isEqualTo(before["summary"])
        assertThat(after["title"]).isEqualTo(before["title"])
    }

    @Test
    fun `the text is trimmed, and saving the same text again changes nothing`() {
        val user = newUser()
        val id = transcription(user)

        assertThat(edit(user, id, 0, "   Spaces around.  \n").body!!["text"].asText()).isEqualTo("Spaces around.")
        assertThat(edit(user, id, 0, "Spaces around.").status).isEqualTo(200)

        assertThat(texts(user, id)[0]).isEqualTo("Spaces around.")
    }

    @Test
    fun `the old JSON cell is kept in step with the rows`() {
        val user = newUser()
        val id = transcription(user)

        edit(user, id, 2, "Corrected third.")

        val cell = jdbcTemplate.queryForObject("SELECT segments::text FROM transcripts WHERE submission_id = ?", String::class.java, id)!!
        val phrases = json.readTree(cell)
        assertThat(phrases[2]["text"].asText()).isEqualTo("Corrected third.")
        assertThat(phrases[0]["text"].asText()).isEqualTo("First phrase.")
        assertThat(phrases[2]["speaker"].asText()).isEqualTo("SPEAKER_00")
    }

    @Test
    fun `an empty, blank, missing or too long text is refused and nothing changes`() {
        val user = newUser()
        val id = transcription(user)

        assertThat(edit(user, id, 0, "").status).isEqualTo(400)
        assertThat(edit(user, id, 0, "   \n ").status).isEqualTo(400)
        assertThat(edit(user, id, 0, null).status).isEqualTo(400)
        assertThat(edit(user, id, 0, "x".repeat(5001)).status).isEqualTo(400)
        assertThat(edit(user, id, 0, "ok\u0000bad").status).isEqualTo(400)
        assertThat(edit(user, id, 0, "x".repeat(5000)).status).isEqualTo(200) // the limit itself is allowed

        assertThat(texts(user, id).drop(1)).containsExactly("Send it by Fryday.", "Third phrase.")
    }

    @Test
    fun `a phrase that is not there, a malformed index and a malformed id are not found`() {
        val user = newUser()
        val id = transcription(user)

        assertThat(edit(user, id, 3, "x").status).isEqualTo(404)
        assertThat(edit(user, id, 99, "x").status).isEqualTo(404)
        assertThat(edit(user, id, -1, "x").status).isEqualTo(404)
        assertThat(edit(user, id, "first", "x").status).isEqualTo(404)
        assertThat(edit(user, "not-an-id", 0, "x").status).isEqualTo(404)
        assertThat(edit(user, UUID.randomUUID(), 0, "x").status).isEqualTo(404)
    }

    @Test
    fun `someone else's transcription cannot be edited`() {
        val owner = newUser()
        val stranger = newUser()
        val id = transcription(owner)

        assertThat(edit(stranger, id, 0, "hijacked").status).isEqualTo(404)

        assertThat(texts(owner, id)[0]).isEqualTo("First phrase.")
    }

    @Test
    fun `without a session, or with an MCP token, it is unauthorized`() {
        val owner = newUser()
        val id = transcription(owner)

        assertThat(edit(null, id, 0, "x").status).isEqualTo(401)
        val mcpToken = "dtr_" + "x".repeat(43)
        val status = send(HttpMethod.PUT, "/transcriptions/$id/segments/0", mcpToken, """{"text":"x"}""").status
        assertThat(status).isEqualTo(401)
        assertThat(texts(owner, id)[0]).isEqualTo("First phrase.")
    }

    @Test
    fun `the corrected text is what the owner gets in the plain-text download`() {
        val user = newUser()
        val id = transcription(user)
        edit(user, id, 1, "Send it by Friday.")

        val response = rest.exchange(
            "/transcript?jobId=$id", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(jwt.issue(user)) }), String::class.java,
        )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).contains("Send it by Friday.").doesNotContain("Fryday")
    }
}
