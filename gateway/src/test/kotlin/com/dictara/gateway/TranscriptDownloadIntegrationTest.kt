package com.dictara.gateway

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
import org.springframework.core.io.ByteArrayResource
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
class TranscriptDownloadIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate

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

    private fun fakeAudio() = object : ByteArrayResource(ByteArray(8)) {
        override fun getFilename() = "audio.m4a"
    }

    private fun submitAndWaitDone(jobStub: String, formattedText: String): UUID {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"$jobStub"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/$jobStub"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,
                "result":{"formatted_text":"$formattedText","audio_duration_s":3.0,"segments":[]}}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val jobId = UUID.fromString(
            rest.postForEntity(
                "/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java,
            ).body!!["job_id"] as String
        )

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val status = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!["status"]
            if (status == "done") return jobId
            Thread.sleep(100)
        }
        error("Job $jobId did not reach done")
    }

    @Test
    fun `unknown job id returns 404`() {
        val resp = rest.getForEntity("/transcript?jobId=${UUID.randomUUID()}", String::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `done job returns transcript as plain text attachment`() {
        val expectedText = "Hello from transcript."
        val jobId = submitAndWaitDone("dl-job-1", expectedText)

        val resp = rest.getForEntity("/transcript?jobId=$jobId", ByteArray::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.headers.contentType).isEqualTo(MediaType.TEXT_PLAIN)
        assertThat(resp.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .contains("attachment")
            .contains("transcript_$jobId.txt")
        assertThat(String(resp.body!!, Charsets.UTF_8)).isEqualTo(expectedText)
    }

    @Test
    fun `done job with diarization returns diarized text`() {
        val diarizedText = "[SPEAKER_00]: Hello.\n[SPEAKER_01]: World."
        val jobStub = "dl-diar-1"
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"$jobStub"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/$jobStub"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,
                "result":{"formatted_text":"$diarizedText","audio_duration_s":3.0,
                          "segments":[{"start":0.0,"end":1.0,"text":"Hello.","speaker":"SPEAKER_00"},
                                      {"start":1.0,"end":2.0,"text":"World.","speaker":"SPEAKER_01"}]}}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val jobId = UUID.fromString(
            rest.postForEntity(
                "/transcribe?model=fast&diarize=true&summary_mode=off",
                HttpEntity(body, headers), Map::class.java,
            ).body!!["job_id"] as String
        )

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val status = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!["status"]
            if (status == "done") break
            Thread.sleep(100)
        }

        val resp = rest.getForEntity("/transcript?jobId=$jobId", ByteArray::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(String(resp.body!!, Charsets.UTF_8)).isEqualTo(diarizedText)
    }
}
