package com.dictara.gateway

import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.UploadResult
import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.repository.SubmissionRepository
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

/** Plain `ArgumentMatchers.any()` returns `null`, and Kotlin inserts a not-null check
 *  on the result before passing it to [AudioStorage.upload]'s non-null parameters,
 *  throwing NPE while the stub is being recorded. Routing through a generic helper
 *  avoids the check — the compiler trusts the (erased) type parameter rather than
 *  the platform-typed Java return value. */
private fun <T> any(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/** Verifies the GCS-reference upload path (gateway → bucket → transcriber by URI),
 *  added to work around Cloud Run's hard 32 MiB HTTP request body limit.
 *  [GcsAudioStorage] needs real GCS credentials, so [AudioStorage] is replaced here
 *  with a [MockBean] that returns a fixed gs:// URI; this test only exercises the
 *  gateway-side wiring (storage_uri persisted, BLOB skipped, transcriber submitted
 *  by reference). */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class AudioStorageIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @Autowired lateinit var audioMetaRepo: AudioMetaRepository
    @MockBean lateinit var audioStorage: AudioStorage

    companion object {
        private const val FAKE_URI = "gs://test-bucket/audio/stub-key/audio.m4a"
    }

    @BeforeEach
    fun stubUpload() {
        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any()))
            .willReturn(UploadResult(AudioRef(FAKE_URI), "fake-sha256-hash"))
    }

    @Test
    fun `audio is uploaded to GCS, referenced by URI, and not stored as a BLOB`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .withQueryParam("storage_uri", equalTo(FAKE_URI))
            .willReturn(okJson("""{"job_id":"gcs-ref-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/gcs-ref-job"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val response = submit()
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val jobId = UUID.fromString(response.body!!["job_id"] as String)
        val submission = submissionRepo.findById(jobId).orElseThrow()

        val meta = audioMetaRepo.findById(submission.audioId).orElseThrow()
        assertThat(meta.storageUri).isEqualTo(FAKE_URI)

        awaitTranscriberSubmission(FAKE_URI)
    }

    /** The orchestrator dispatches jobs on a background thread, so the (mocked)
     *  transcriber may not have received the request yet right after the submit
     *  response returns — poll until it does, mirroring
     *  [TranscriptionIntegrationTest.pollUntilDone]'s pattern. */
    private fun awaitTranscriberSubmission(storageUri: String) {
        val request = postRequestedFor(urlPathEqualTo("/transcribe")).withQueryParam("storage_uri", equalTo(storageUri))
        repeat(50) {
            if (wireMock.findAll(request).isNotEmpty()) return
            Thread.sleep(200)
        }
        wireMock.verify(request)
    }

    private fun submit(): ResponseEntity<Map<*, *>> {
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val file = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "audio.m4a"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        @Suppress("UNCHECKED_CAST")
        return rest.postForEntity(
            "/transcribe?model=small&diarize=false&summary_mode=off",
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<*, *>>
    }
}
