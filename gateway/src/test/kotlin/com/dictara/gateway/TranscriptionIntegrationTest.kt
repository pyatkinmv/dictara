package com.dictara.gateway

import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ClassPathResource
import org.springframework.http.*
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class TranscriptionIntegrationTest {

    @Autowired
    lateinit var rest: TestRestTemplate

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @RegisterExtension
        @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("dictara.transcriber.url") { wireMock.baseUrl() }
            registry.add("dictara.transcriber.poll-interval-ms") { "100" }
        }
    }

    // ── DTOs for deserializing gateway responses ───────────────────────────────

    data class SubmitResponse(val jobId: String)

    data class Segment(val start: Double, val end: Double, val text: String, val speaker: String?)

    data class ResultResponse(
        val formattedText: String?,
        val audioDurationS: Double?,
        val segments: List<Segment>?,
        val summary: String?,
    )

    data class JobResponse(
        val jobId: String,
        val status: String,
        val result: ResultResponse?,
        val error: String?,
    )

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `happy path - transcription without diarization`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/transcribe"))
                .willReturn(okJson("""{"job_id":"job-1"}"""))
        )
        wireMock.stubFor(
            get(urlEqualTo("/jobs/job-1"))
                .inScenario("job-1").whenScenarioStateIs("Started")
                .willReturn(okJson("""
                    {"status":"processing","elapsed_s":0.5,
                     "progress":{"phase":"transcribing","processed_s":1.0,"total_s":5.0}}
                """))
                .willSetStateTo("done")
        )
        wireMock.stubFor(
            get(urlEqualTo("/jobs/job-1"))
                .inScenario("job-1").whenScenarioStateIs("done")
                .willReturn(okJson("""
                    {"status":"done","duration_s":2.1,
                     "result":{"formatted_text":"Hello world.","audio_duration_s":5.0,
                               "segments":[{"start":0.0,"end":2.0,"text":"Hello world."}]}}
                """))
        )

        val jobId = submitAudio(diarize = false)
        val result = pollUntilDone(jobId)

        assert(result.formattedText?.isNotEmpty() == true) { "formattedText should not be empty" }
        assert(result.audioDurationS != null) { "audioDurationS should be present" }
        assert(result.segments?.isNotEmpty() == true) { "segments should not be empty" }
    }

    @Test
    fun `happy path - transcription with diarization`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/transcribe"))
                .willReturn(okJson("""{"job_id":"job-2"}"""))
        )
        wireMock.stubFor(
            get(urlEqualTo("/jobs/job-2"))
                .willReturn(okJson("""
                    {"status":"done","duration_s":2.1,
                     "result":{"formatted_text":"[SPEAKER_00]: Hello world.","audio_duration_s":5.0,
                               "segments":[{"start":0.0,"end":2.0,"text":"Hello world.","speaker":"SPEAKER_00"}]}}
                """))
        )

        val jobId = submitAudio(diarize = true)
        val result = pollUntilDone(jobId)

        assert(result.segments?.any { it.speaker != null } == true) { "at least one segment should have a speaker label" }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun submitAudio(diarize: Boolean): String {
        val body = LinkedMultiValueMap<String, Any>()
        body.add("file", ClassPathResource("test-audio.m4a"))
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", "integration-test-user")
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=$diarize&summary_mode=off",
            HttpEntity(body, headers),
            SubmitResponse::class.java,
        )
        check(response.statusCode == HttpStatus.ACCEPTED) { "Expected 202 Accepted, got ${response.statusCode}" }
        return checkNotNull(response.body?.jobId) { "Response body missing job_id" }
    }

    private fun pollUntilDone(jobId: String): ResultResponse {
        repeat(50) {
            val response = rest.getForEntity("/jobs/$jobId", JobResponse::class.java)
            val body = response.body ?: error("Empty response for job $jobId")
            when (body.status) {
                "done" -> return checkNotNull(body.result) { "status=done but result is null" }
                "failed" -> error("Job $jobId failed: ${body.error}")
            }
            Thread.sleep(200)
        }
        error("Job $jobId did not complete within timeout")
    }
}
