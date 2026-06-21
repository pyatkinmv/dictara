package com.dictara.gateway

import com.dictara.gateway.repository.AudioMetaRepository
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

/** See [AudioStorageIntegrationTest] for why this helper is needed. */
private fun <T> any(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

@SpringBootTest(webEnvironment = RANDOM_PORT)
class DeduplicationIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @MockBean lateinit var audioStorage: AudioStorage

    companion object {
        private const val FAKE_URI = "gs://test-bucket/audio/stub/audio.m4a"
        private const val HASH_A = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111"
        private const val HASH_B = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222"
        private const val DONE_STUB = """{"status":"done","duration_s":0.1,"result":{"formatted_text":"test","audio_duration_s":1.0,"segments":[{"start":0.0,"end":1.0,"text":"test"}]}}"""
    }

    private val wireMock get() = SharedTestInfrastructure.wireMock

    @BeforeEach
    fun setup() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"stub-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job"))
            .willReturn(okJson("""{"status":"processing"}""")))
        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any()))
            .willReturn(UploadResult(AudioRef(FAKE_URI), HASH_A))
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun submit(
        model: String = "small",
        diarize: Boolean = false,
        summaryMode: String = "off",
    ): ResponseEntity<Map<*, *>> {
        val file = object : ByteArrayResource(ByteArray(8)) { override fun getFilename() = "audio.m4a" }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        @Suppress("UNCHECKED_CAST")
        return rest.postForEntity(
            "/transcribe?model=$model&diarize=$diarize&summary_mode=$summaryMode",
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<*, *>>
    }

    private fun jobId(response: ResponseEntity<Map<*, *>>): UUID =
        UUID.fromString(response.body!!["job_id"] as String)

    /** Polls WireMock until at least [minCount] POST /transcribe requests arrive.
     *  Needed because OrchestratorService dispatches jobs on a background thread. */
    private fun awaitTranscriberCalls(minCount: Int) {
        val request = postRequestedFor(urlPathEqualTo("/transcribe"))
        repeat(50) {
            if (wireMock.findAll(request).size >= minCount) return
            Thread.sleep(200)
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `duplicate upload with same settings returns existing job_id without re-transcribing`() {
        val first = submit()
        awaitTranscriberCalls(1)  // wait for first submission to reach transcriber before second submit
        val second = submit()

        assertThat(first.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(jobId(second)).isEqualTo(jobId(first))

        // Transcriber must be called only once — not for the duplicate
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/transcribe")))
    }

    @Test
    fun `same file with different model is not a duplicate`() {
        // Override to "done" so OrchestratorService completes the first dispatch and picks up the second.
        // The default "processing" stub would keep submission A stuck, blocking B via idx_one_processing_submission.
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson(DONE_STUB)))

        val first = submit(model = "small")
        val second = submit(model = "turbo")

        awaitTranscriberCalls(2)
        assertThat(jobId(first)).isNotEqualTo(jobId(second))
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/transcribe")))
    }

    @Test
    fun `same file with different diarize setting is not a duplicate`() {
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson(DONE_STUB)))

        val first = submit(diarize = false)
        val second = submit(diarize = true)

        awaitTranscriberCalls(2)
        assertThat(jobId(first)).isNotEqualTo(jobId(second))
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/transcribe")))
    }

    @Test
    fun `failed submission does not block re-upload of same file`() {
        val first = submit()
        val firstId = jobId(first)

        val submission = submissionRepo.findById(firstId).orElseThrow()
        submission.status = "failed"
        submissionRepo.save(submission)

        val second = submit()

        assertThat(jobId(second)).isNotEqualTo(firstId)
    }

    @Test
    fun `content_hash is persisted to audio_meta after upload`() {
        val response = submit()
        val submission = submissionRepo.findById(jobId(response)).orElseThrow()
        val meta = audioMetaRepo.findById(submission.audio.id!!).orElseThrow()

        assertThat(meta.contentHash).isEqualTo(HASH_A)
    }

    @Test
    fun `different file content produces separate submissions`() {
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job")).willReturn(okJson(DONE_STUB)))

        val first = submit()

        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any()))
            .willReturn(UploadResult(AudioRef(FAKE_URI), HASH_B))
        val second = submit()

        awaitTranscriberCalls(2)
        assertThat(jobId(first)).isNotEqualTo(jobId(second))
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/transcribe")))
    }
}
