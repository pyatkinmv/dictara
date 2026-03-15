package com.dictara.gateway

import com.dictara.gateway.repository.StageAttemptRepository
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class PersistenceIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissionRepo: SubmissionRepository
    @Autowired lateinit var transcriptRepo: TranscriptRepository
    @Autowired lateinit var stageAttemptRepo: StageAttemptRepository

    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @RegisterExtension @JvmField
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build()

        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("dictara.transcriber.url") { wireMock.baseUrl() }
            registry.add("dictara.transcriber.poll-interval-ms") { "100" }
        }
    }

    private fun submitFile(chatId: String = "test-user"): UUID {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"persist-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/persist-1"))
            .willReturn(okJson("""
                {"status":"done","duration_s":1.5,
                 "result":{"formatted_text":"Hello world.","audio_duration_s":5.0,
                           "segments":[{"start":0.0,"end":2.0,"text":"Hello world."}]}}
            """)))
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", ClassPathResource("test-audio.m4a")) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", chatId)
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=off",
            HttpEntity(body, headers), Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        return UUID.fromString(response.body!!["jobId"] as String)
    }

    private fun waitForStatus(jobId: UUID, targetStatus: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val jobResp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
            if (jobResp["status"] == targetStatus) return
            Thread.sleep(200)
        }
        error("Job $jobId did not reach status=$targetStatus within timeout")
    }

    @Test
    fun `submission and transcript are persisted to DB after successful transcription`() {
        val jobId = submitFile()
        waitForStatus(jobId, "done")

        val submission = submissionRepo.findById(jobId).orElse(null)
        assertThat(submission).isNotNull
        assertThat(submission.status).isEqualTo("done")

        val transcript = transcriptRepo.findBySubmissionId(jobId)
        assertThat(transcript).isNotNull
        assertThat(transcript!!.formattedText).contains("Hello world")

        val attempts = stageAttemptRepo.findBySubmissionIdAndStageOrderByAttemptNumDesc(jobId, "transcription")
        assertThat(attempts).hasSize(1)
        assertThat(attempts[0].status).isEqualTo("done")
    }

    @Test
    fun `transcription retries up to 3 times then marks submission failed`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"fail-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/fail-job"))
            .willReturn(okJson("""{"status":"failed","error":"GPU out of memory"}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", ClassPathResource("test-audio.m4a")) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", "retry-test-user")
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=off",
            HttpEntity(body, headers), Map::class.java,
        )
        val jobId = UUID.fromString(response.body!!["jobId"] as String)

        waitForStatus(jobId, "failed")

        val submission = submissionRepo.findById(jobId).orElse(null)
        assertThat(submission?.status).isEqualTo("failed")

        val attempts = stageAttemptRepo.findBySubmissionIdAndStageOrderByAttemptNumDesc(jobId, "transcription")
        assertThat(attempts).hasSize(3)
        assertThat(attempts.all { it.status == "failed" }).isTrue
    }
}
