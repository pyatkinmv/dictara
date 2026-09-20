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

/** `GET /transcriptions/{id}` (what the detail page shows) and the title rules of `PATCH /jobs/{id}/title`. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionDetailIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID

    @BeforeEach
    fun createUser() {
        userId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
    }

    private fun addSubmission(
        owner: UUID = userId,
        name: String = "call.mp3",
        status: String = "done",
        segments: String? = null,
        title: String? = null,
        language: String? = "en",
        summary: String? = null,
    ): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, ?, 'audio/mpeg', 13800000, ?, ?, 862.5)",
            audioId, owner, name, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', ?)",
            id, owner, audioId, status,
        )
        if (segments != null) {
            jdbcTemplate.update(
                "INSERT INTO transcripts(id, submission_id, segments, detected_language, title) VALUES (?, ?, ?::jsonb, ?, ?)",
                UUID.randomUUID(), id, segments, language, title,
            )
        }
        if (summary != null) jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, ?)", id, summary)
        return id
    }

    private fun get(id: Any, asUser: UUID? = userId): Pair<HttpStatus, JsonNode?> {
        val headers = HttpHeaders().apply { asUser?.let { setBearerAuth(jwt.issue(it)) } }
        val response = rest.exchange("/transcriptions/$id", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        val body = response.body?.takeIf { it.startsWith("{") }?.let(json::readTree)
        return HttpStatus.valueOf(response.statusCode.value()) to body
    }

    private fun rename(id: UUID, body: String) = rest.exchange(
        "/jobs/$id/title", HttpMethod.PATCH,
        HttpEntity(body, HttpHeaders().apply { setBearerAuth(jwt.issue(userId)); contentType = MediaType.APPLICATION_JSON }),
        String::class.java,
    )

    private val twoSpeakers = """[
        {"start": 0, "end": 30.5, "text": "Good morning everyone.  Let's start", "speaker": "SPEAKER_00"},
        {"start": 34, "end": 70, "text": "Right. Q2 was solid", "speaker": "SPEAKER_01"}
    ]"""

    @Test
    fun `a finished transcription comes with its facts, segments, summary and tags`() {
        val id = addSubmission(name = "review.m4a", segments = twoSpeakers, title = "Quarterly Review", summary = "**Key points**\n* one")
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, 'meeting')", UUID.randomUUID(), userId)
        jdbcTemplate.update(
            "INSERT INTO transcript_tags(transcript_id, tag_id) SELECT t.id, g.id FROM transcripts t, tags g WHERE t.submission_id = ? AND g.user_id = ?",
            id, userId,
        )

        val (status, body) = get(id)

        assertThat(status).isEqualTo(HttpStatus.OK)
        body!!
        assertThat(body["job_id"].asText()).isEqualTo(id.toString())
        assertThat(body["title"].asText()).isEqualTo("Quarterly Review")
        assertThat(body["file_name"].asText()).isEqualTo("review.m4a")
        assertThat(body["file_size_bytes"].asLong()).isEqualTo(13_800_000)
        assertThat(body["status"].asText()).isEqualTo("done")
        assertThat(body["audio_duration_s"].asDouble()).isEqualTo(862.5)
        assertThat(body["language"].asText()).isEqualTo("en")
        assertThat(body["word_count"].asInt()).isEqualTo(9) // words, however much space is between them
        assertThat(body["tags"].map { it["label"].asText() }).containsExactly("meeting")
        assertThat(body["tags"][0]["color"].asText()).matches("#[0-9A-F]{6}")
        assertThat(UUID.fromString(body["tags"][0]["id"].asText())).isNotNull()
        assertThat(body["summary"].asText()).isEqualTo("**Key points**\n* one")
        assertThat(body["segments"]).hasSize(2)
        assertThat(body["segments"][0]["speaker"].asText()).isEqualTo("SPEAKER_00")
        assertThat(body["segments"][1]["start"].asDouble()).isEqualTo(34.0)
        assertThat(body["segments"][1]["end"].asDouble()).isEqualTo(70.0)
        assertThat(body["segments"][1]["text"].asText()).isEqualTo("Right. Q2 was solid")
    }

    @Test
    fun `without a summary the field is absent, without a title the file name stands in`() {
        val id = addSubmission(name = "plain.mp3", segments = """[{"start": 0, "end": 1, "text": "hi", "speaker": null}]""")

        val body = get(id).second!!

        assertThat(body.has("summary")).describedAs("nulls are left out").isFalse()
        assertThat(body["title"].asText()).isEqualTo("plain.mp3")
        assertThat(body["segments"][0].has("speaker")).describedAs("no diarization").isFalse()
    }

    @Test
    fun `a job with no transcript yet has no segments`() {
        val id = addSubmission(status = "processing")

        val body = get(id).second!!

        assertThat(body["status"].asText()).isEqualTo("processing")
        assertThat(body["segments"]).isEmpty()
        assertThat(body["word_count"].asInt()).isZero()
        assertThat(body["tags"]).isEmpty()
    }

    @Test
    fun `someone else's transcription, a missing one and a malformed id are all 404`() {
        val others = addSubmission(owner = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }, segments = "[]")

        assertThat(get(others).first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(UUID.randomUUID()).first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("not-a-uuid").first).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `without a token there is no detail`() {
        val id = addSubmission(segments = "[]")

        assertThat(get(id, asUser = null).first).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a title is trimmed and one that is too long is a 400`() {
        val id = addSubmission(segments = "[]")

        assertThat(rename(id, """{"title": "  New name  "}""").statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(id).second!!["title"].asText()).isEqualTo("New name")

        assertThat(rename(id, """{"title": "${"x".repeat(201)}"}""").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(rename(id, """{"title": "${"x".repeat(200)}"}""").statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get(id).second!!["title"].asText()).hasSize(200)
    }

    @Test
    fun `an empty title puts the file name back`() {
        val id = addSubmission(name = "file.mp3", segments = "[]", title = "Custom")

        assertThat(rename(id, """{"title": "  "}""").statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(get(id).second!!["title"].asText()).isEqualTo("file.mp3")
    }
}
