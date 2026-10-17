package com.dictara.gateway

import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.UploadResult
import com.github.tomakehurst.wiremock.client.WireMock.*
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
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import java.util.UUID

private fun <T> any(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * TODO(remove-restore-only): delete this class with the mode (see DictaraProperties.restoreOnly).
 *
 * The temporary mode for bringing the old audio back: with `dictara.restore-only=true` a file the user already has a
 * finished or running transcription of, by exactly these bytes, is answered with that transcription whatever options
 * come with it. Every other file is transcribed as usual.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["dictara.restore-only=true"])
class RestoreOnlyModeIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @MockBean lateinit var audioStorage: AudioStorage

    companion object {
        private const val URI = "gs://test-bucket/audio/stub/audio.m4a"
        private const val HASH_A = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"
        private const val HASH_B = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"
        private const val DONE_STUB = """{"status":"done","duration_s":0.1,"result":{"formatted_text":"test","audio_duration_s":1.0,"segments":[{"start":0.0,"end":1.0,"text":"test"}]}}"""
    }

    @BeforeEach
    fun setup() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe")).willReturn(okJson("""{"job_id":"stub-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson(DONE_STUB)))
        hashIs(HASH_A)
    }

    private fun hashIs(hash: String) {
        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any()))
            .willReturn(UploadResult(AudioRef(URI), hash))
    }

    private fun submit(model: String = "small", diarize: Boolean = false, summaryMode: String = "off"): ResponseEntity<Map<*, *>> {
        val file = object : ByteArrayResource(ByteArray(8)) { override fun getFilename() = "audio.m4a" }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        @Suppress("UNCHECKED_CAST")
        return rest.postForEntity(
            "/transcribe?model=$model&diarize=$diarize&summary_mode=$summaryMode",
            HttpEntity(body, headers), Map::class.java,
        ) as ResponseEntity<Map<*, *>>
    }

    private fun jobId(response: ResponseEntity<Map<*, *>>): UUID = UUID.fromString(response.body!!["job_id"] as String)

    private fun awaitTranscriberCalls(minCount: Int) {
        val request = postRequestedFor(urlPathEqualTo("/transcribe"))
        repeat(50) {
            if (wireMock.findAll(request).size >= minCount) return
            Thread.sleep(200)
        }
    }

    private fun awaitStatus(id: UUID, status: String) {
        repeat(100) {
            if (jdbcTemplate.queryForObject("SELECT status FROM submissions WHERE id = ?", String::class.java, id) == status) return
            Thread.sleep(100)
        }
        throw AssertionError("Submission $id did not reach $status")
    }

    private fun transcriberCalls() = wireMock.findAll(postRequestedFor(urlPathEqualTo("/transcribe"))).size

    @Test
    fun `the same file with other options is answered with the transcription that exists, not transcribed again`() {
        val first = submit(model = "small", diarize = false, summaryMode = "off")
        awaitTranscriberCalls(1)

        val second = submit(model = "turbo", diarize = true, summaryMode = "full")

        assertThat(second.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.body!!["dedup"]).isEqualTo(true)
        assertThat(jobId(second)).isEqualTo(jobId(first))
        assertThat(submissionRepo.count()).isEqualTo(1L)
        assertThat(transcriberCalls()).isEqualTo(1)
    }

    @Test
    fun `a transcription that is still running is answered too`() {
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson("""{"status":"processing"}""")))
        val first = submit()
        awaitTranscriberCalls(1)

        val second = submit(model = "turbo", diarize = true)

        assertThat(jobId(second)).isEqualTo(jobId(first))
        assertThat(transcriberCalls()).isEqualTo(1)
    }

    @Test
    fun `many copies with different options all get the one transcription`() {
        val first = submit()
        awaitTranscriberCalls(1)

        val others = listOf(
            submit(model = "turbo"), submit(diarize = true), submit(summaryMode = "full"),
            submit(model = "turbo", diarize = true, summaryMode = "brief"),
        )

        assertThat(others.map { jobId(it) }.toSet()).containsExactly(jobId(first))
        assertThat(submissionRepo.count()).isEqualTo(1L)
        assertThat(transcriberCalls()).isEqualTo(1)
    }

    @Test
    fun `a file with bytes nobody has sent is transcribed as usual`() {
        val first = submit()
        awaitTranscriberCalls(1)
        hashIs(HASH_B) // other bytes

        val second = submit(model = "turbo", diarize = true)

        assertThat(second.body!!["dedup"]).isEqualTo(false)
        assertThat(jobId(second)).isNotEqualTo(jobId(first))
        awaitTranscriberCalls(2)
        assertThat(transcriberCalls()).isEqualTo(2)
    }

    @Test
    fun `a failed transcription is no answer, the file is transcribed again`() {
        val first = submit()
        awaitTranscriberCalls(1)
        awaitStatus(jobId(first), "done") // the background dispatch must be over, or it would overwrite what is set below
        jdbcTemplate.update("UPDATE submissions SET status = 'failed' WHERE id = ?", jobId(first))

        val second = submit(model = "turbo", diarize = true)

        assertThat(second.body!!["dedup"]).isEqualTo(false)
        assertThat(jobId(second)).isNotEqualTo(jobId(first))
    }

    @Test
    fun `another user's transcription of the same bytes is not an answer for this user`() {
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        val audio = UUID.randomUUID()
        val theirs = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'x.m4a', 'audio/mp4', 8, ?, ?)",
            audio, other, URI, HASH_A,
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'turbo', 'auto', true, 'auto', 'telegram', 'done')",
            theirs, other, audio,
        )

        val mine = submit()

        assertThat(mine.body!!["dedup"]).isEqualTo(false)
        assertThat(jobId(mine)).isNotEqualTo(theirs)
    }

    @Test
    fun `a hash that is not a SHA-256 is never taken for a match`() {
        hashIs("not-a-real-hash")
        val first = submit()
        awaitTranscriberCalls(1)

        val second = submit(model = "turbo", diarize = true)

        assertThat(second.body!!["dedup"]).isEqualTo(false)
        assertThat(jobId(second)).isNotEqualTo(jobId(first))
    }

    @Test
    fun `the same options still give the same answer as without the mode`() {
        val first = submit()
        awaitTranscriberCalls(1)

        val second = submit()

        assertThat(jobId(second)).isEqualTo(jobId(first))
        assertThat(transcriberCalls()).isEqualTo(1)
    }
}
