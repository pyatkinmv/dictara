package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.UploadResult
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private fun <T> any(): T { ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }

/** `POST /transcriptions`: what the web client's Upload dialog sends, and what becomes of it. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionUploadIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        userId = newUser()
        // The storage answers with the SHA-256 of what it was given, like the real one, so equal files are equal.
        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any())).willAnswer { call ->
            val content = call.getArgument<InputStream>(2).readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            UploadResult(AudioRef("gs://test-bucket/${call.getArgument<UUID>(0)}"), hash)
        }
        // Whatever is accepted stays "processing" at the (fake) transcriber, so the tests see it as it was accepted.
        wireMock.stubFor(post(urlPathEqualTo("/transcribe")).willReturn(okJson("""{"job_id":"stub-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson("""{"status":"processing"}""")))
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private class Reply(val status: HttpStatus, val body: JsonNode?)

    private fun upload(
        options: String = "",
        content: ByteArray = "some audio".toByteArray(),
        fileName: String = "call.mp3",
        user: UUID? = userId,
    ): Reply {
        val file = object : ByteArrayResource(content) {
            override fun getFilename() = fileName
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            user?.let { setBearerAuth(jwt.issue(it)) }
        }
        val response = rest.postForEntity("/transcriptions$options", HttpEntity(body, headers), String::class.java)
        return Reply(HttpStatus.valueOf(response.statusCode.value()), response.body?.takeIf { it.startsWith("{") }?.let(json::readTree))
    }

    private fun submission(id: JsonNode) = jdbcTemplate.queryForMap(
        "SELECT s.model, s.language_hint, s.diarize, s.num_speakers, s.summary_mode, s.source, s.user_id, a.original_name FROM submissions s JOIN audio_meta a ON a.id = s.audio_id WHERE s.id = ?::uuid",
        id.asText(),
    )

    private fun countSubmissions(user: UUID = userId) =
        jdbcTemplate.queryForObject("SELECT count(*) FROM submissions WHERE user_id = ?", Int::class.java, user)

    // ── An accepted upload ─────────────────────────────────────────────────────

    @Test
    fun `an upload without options is accepted with the defaults of the web client`() {
        val reply = upload()

        assertThat(reply.status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(reply.body!!["duplicate"].asBoolean()).isFalse()
        val item = reply.body["item"]
        assertThat(item["file_name"].asText()).isEqualTo("call.mp3")
        assertThat(item["title"].asText()).isEqualTo("call.mp3")
        assertThat(item["status"].asText()).isIn("pending", "processing") // the queue may already have picked it up
        assertThat(item["tags"]).isEmpty()
        val row = submission(item["job_id"])
        assertThat(row["model"]).isEqualTo("turbo") // "accurate"
        assertThat(row["language_hint"]).isEqualTo("auto")
        assertThat(row["diarize"]).isEqualTo(true)
        assertThat(row["num_speakers"]).isNull()
        assertThat(row["summary_mode"]).isEqualTo("auto")
        assertThat(row["source"]).isEqualTo("web")
        assertThat(row["user_id"]).isEqualTo(userId)
    }

    @Test
    fun `the options of the dialog reach the submission`() {
        val reply = upload("?language=RU&summary=Concise&diarize=true&num_speakers=3&model=fast")

        val row = submission(reply.body!!["item"]["job_id"])
        assertThat(row["language_hint"]).isEqualTo("ru")
        assertThat(row["summary_mode"]).isEqualTo("concise")
        assertThat(row["diarize"]).isEqualTo(true)
        assertThat(row["num_speakers"]).isEqualTo(3)
        assertThat(row["model"]).isEqualTo("small")
    }

    @Test
    fun `every summary mode is accepted and stored in lower case`() {
        for (mode in listOf("off", "auto", "brief", "concise", "full")) {
            val reply = upload("?summary=$mode", content = "content for $mode".toByteArray())
            assertThat(submission(reply.body!!["item"]["job_id"])["summary_mode"]).describedAs(mode).isEqualTo(mode)
        }
    }

    @Test
    fun `turning diarization off drops the speaker count`() {
        val reply = upload("?diarize=false&num_speakers=3")

        val row = submission(reply.body!!["item"]["job_id"])
        assertThat(row["diarize"]).isEqualTo(false)
        assertThat(row["num_speakers"]).isNull()
    }

    @Test
    fun `an upload is picked up by the transcriber's queue`() {
        val reply = upload()

        val status = jdbcTemplate.queryForObject("SELECT status FROM submissions WHERE id = ?::uuid", String::class.java, reply.body!!["item"]["job_id"].asText())
        assertThat(status).isIn("pending", "processing")
    }

    // ── Refusals ───────────────────────────────────────────────────────────────

    @Test
    fun `without a token there is no upload`() {
        assertThat(upload(user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(countSubmissions()).isZero()
    }

    @Test
    fun `an unsupported format, a picture with an audio name, or an unknown option is a 400 and creates nothing`() {
        assertThat(upload(fileName = "notes.txt").status).describedAs("txt").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload(fileName = "photo.mp3", content = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)).status).describedAs("jpeg").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload("?summary=huge").status).describedAs("summary").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload("?language=Russian!").status).describedAs("language").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload("?model=turbo").status).describedAs("model").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload("?num_speakers=0").status).describedAs("0 speakers").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(upload("?num_speakers=11").status).describedAs("11 speakers").isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(countSubmissions()).isZero()
    }

    @Test
    fun `the monthly limit of the free plan is a 429 that can be shown as it is`() {
        repeat(10) { upload(content = "file $it".toByteArray()) }
        assertThat(countSubmissions()).isEqualTo(10)

        val reply = upload(content = "the eleventh".toByteArray())

        assertThat(reply.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(reply.body!!["message"].asText()).startsWith("Monthly transcription limit reached (10/month)")
        assertThat(countSubmissions()).isEqualTo(10)
    }

    // ── The same file again ────────────────────────────────────────────────────

    @Test
    fun `the same file with the same options is not transcribed again, the existing entry is the answer`() {
        val first = upload("?language=ru")

        val second = upload("?language=ru")

        assertThat(second.status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.body!!["duplicate"].asBoolean()).isTrue()
        assertThat(second.body["item"]["job_id"].asText()).isEqualTo(first.body!!["item"]["job_id"].asText())
        assertThat(countSubmissions()).isEqualTo(1)
    }

    @Test
    fun `a duplicate of a finished transcription answers with it as it is now`() {
        val first = upload()
        val id = first.body!!["item"]["job_id"].asText()
        jdbcTemplate.update("UPDATE submissions SET status = 'done' WHERE id = ?::uuid", id)

        val second = upload()

        assertThat(second.body!!["duplicate"].asBoolean()).isTrue()
        assertThat(second.body["item"]["status"].asText()).isEqualTo("done")
    }

    @Test
    fun `a duplicate does not count against the monthly limit`() {
        repeat(10) { upload(content = "file $it".toByteArray()) }

        val again = upload(content = "file 3".toByteArray())

        assertThat(again.status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(again.body!!["duplicate"].asBoolean()).isTrue()
    }

    @Test
    fun `the same file with other options, by another user, or after a failure is a new transcription`() {
        val first = upload()
        assertThat(upload("?summary=off").body!!["duplicate"].asBoolean()).describedAs("other summary").isFalse()
        assertThat(upload("?diarize=false").body!!["duplicate"].asBoolean()).describedAs("no diarization").isFalse()
        assertThat(upload("?model=fast").body!!["duplicate"].asBoolean()).describedAs("other model").isFalse()
        assertThat(upload(user = newUser()).body!!["duplicate"].asBoolean()).describedAs("other user").isFalse()

        jdbcTemplate.update("UPDATE submissions SET status = 'failed' WHERE id = ?::uuid", first.body!!["item"]["job_id"].asText())
        val retry = upload()

        assertThat(retry.body!!["duplicate"].asBoolean()).describedAs("after a failure").isFalse()
        assertThat(retry.body["item"]["job_id"].asText()).isNotEqualTo(first.body["item"]["job_id"].asText())
    }

    @Test
    fun `the old endpoint for the bot and the old client is untouched`() {
        val file = object : ByteArrayResource("legacy".toByteArray()) {
            override fun getFilename() = "legacy.mp3"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(jwt.issue(userId))
        }

        val response = rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off", HttpEntity(body, headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(json.readTree(response.body).has("job_id")).isTrue()
    }
}
