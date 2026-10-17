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
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.*
import org.springframework.util.LinkedMultiValueMap
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        fileName: String = "audio.m4a",
    ): ResponseEntity<Map<*, *>> {
        val file = object : ByteArrayResource(ByteArray(8)) { override fun getFilename() = fileName }
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
    fun `fresh upload returns dedup=false, duplicate returns dedup=true`() {
        val first = submit()
        awaitTranscriberCalls(1)
        val second = submit()

        assertThat(first.body!!["dedup"]).isEqualTo(false)
        assertThat(second.body!!["dedup"]).isEqualTo(true)
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
        val meta = audioMetaRepo.findById(submission.audioId).orElseThrow()

        assertThat(meta.contentHash).isEqualTo(HASH_A)
    }

    @Test
    fun `two near-simultaneous uploads of the same file do not race past the dedup check`() {
        // SubmissionService.createSubmission does "SELECT existing duplicate, then INSERT if none"; without
        // tryLockForDedup serializing same-key callers, two requests that both pass the SELECT before either
        // commits would both insert, silently doubling the quota charge and the GCS/transcriber work (there is
        // no DB-level uniqueness to fall back on — only a plain, non-unique index backs
        // audio_meta(user_id, content_hash), see V12). A CyclicBarrier lines both threads up at the same
        // instant to make that window as likely as possible to hit if the lock were ever removed.
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                pool.submit<ResponseEntity<Map<*, *>>> {
                    barrier.await(10, TimeUnit.SECONDS)
                    submit()
                }
            }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            val ids = results.map { jobId(it) }.toSet()

            assertThat(ids).describedAs("both requests must resolve to the one submission, not a race").hasSize(1)
            assertThat(results.map { it.body!!["dedup"] }).describedAs("one is the original, one is the dedup hit").containsExactlyInAnyOrder(false, true)
            assertThat(submissionRepo.count()).isEqualTo(1L)
        } finally {
            pool.shutdown()
        }
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

    @Test
    fun `re-sending a file whose stored copy is gone only brings the audio back, whatever options come with it`() {
        val first = submit(model = "small", diarize = false)
        val firstId = jobId(first)
        awaitTranscriberCalls(1) // the dispatch is asynchronous; do not let it spill into the next test
        val restoredUri = "gs://test-bucket/audio/2026-10-15/restored.m4a"
        given(audioStorage.isGone(AudioRef(FAKE_URI))).willReturn(true)
        given(audioStorage.upload(any(), any(), any(), ArgumentMatchers.anyLong(), any()))
            .willReturn(UploadResult(AudioRef(restoredUri), HASH_A))

        val second = submit(model = "turbo", diarize = true) // not the options the record was made with

        assertThat(second.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(second.body!!["dedup"]).isEqualTo(true)
        assertThat(jobId(second)).isEqualTo(firstId)
        assertThat(submissionRepo.count()).isEqualTo(1L) // nothing transcribed again, no second record
        assertThat(audioMetaRepo.findAll().map { it.storageUri }).containsOnly(restoredUri)
    }

    @Test
    fun `a file whose content is already stored is not uploaded again, even when another user stored it`() {
        val realHash = MessageDigest.getInstance("SHA-256").digest(ByteArray(8)).joinToString("") { "%02x".format(it) }
        val liveUri = "gs://test-bucket/audio/2026-10-01/live.m4a"
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'x.m4a', 'audio/mp4', 8, ?, ?)",
            UUID.randomUUID(), other, liveUri, realHash,
        )

        val response = submit()

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body!!["dedup"]).isEqualTo(false) // a record of its own for this user
        verify(audioStorage, never()).upload(any(), any(), any(), ArgumentMatchers.anyLong(), any())
        val submission = submissionRepo.findById(jobId(response)).orElseThrow()
        assertThat(audioMetaRepo.findById(submission.audioId).orElseThrow().storageUri).isEqualTo(liveUri)
        awaitTranscriberCalls(1)
    }

    @Test
    fun `the same file sent again under its real name replaces the made-up name of the old upload`() {
        val first = submit(fileName = "dictara-7802649983095675646.m4a")
        awaitTranscriberCalls(1)

        val second = submit(fileName = "20260608_213900.m4a") // the audio is still there: nothing to restore

        assertThat(second.body!!["dedup"]).isEqualTo(true)
        assertThat(jobId(second)).isEqualTo(jobId(first))
        assertThat(audioMetaRepo.findAll().map { it.originalName }).containsOnly("20260608_213900.m4a")
    }

    @Test
    fun `a name the user kept is not replaced by a later upload`() {
        val first = submit(fileName = "Interview with Anna.m4a")
        awaitTranscriberCalls(1)

        val second = submit(fileName = "20260608_213900.m4a")

        assertThat(jobId(second)).isEqualTo(jobId(first))
        assertThat(audioMetaRepo.findAll().map { it.originalName }).containsOnly("Interview with Anna.m4a")
    }

    @Test
    fun `thirty copies of one file sent at once all get an answer and make one transcription`() {
        // A user forwarding a whole chat of recordings to the bot sends the same file again and again within seconds.
        // Every copy has to be answered (the dedup lock used to give up after five seconds), and none may start
        // a second transcription.
        val barrier = CyclicBarrier(30)
        val pool = Executors.newFixedThreadPool(30)
        try {
            val futures = List(30) {
                pool.submit<ResponseEntity<Map<*, *>>> {
                    barrier.await(20, TimeUnit.SECONDS)
                    submit(fileName = "copy-$it.m4a")
                }
            }
            val results = futures.map { it.get(90, TimeUnit.SECONDS) }

            assertThat(results.map { it.statusCode }).containsOnly(HttpStatus.ACCEPTED)
            assertThat(results.map { jobId(it) }.toSet()).describedAs("one submission for all of them").hasSize(1)
            assertThat(results.count { it.body!!["dedup"] == false }).describedAs("only the first is new").isEqualTo(1)
            assertThat(submissionRepo.count()).isEqualTo(1L)
            awaitTranscriberCalls(1)
            wireMock.verify(1, postRequestedFor(urlPathEqualTo("/transcribe")))
        } finally {
            pool.shutdown()
        }
    }
}
