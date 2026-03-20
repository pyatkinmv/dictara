package com.dictara.gateway

import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.repository.SubmissionRepository
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
class SubmitIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissionRepo: SubmissionRepository

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

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun submit(
        model: String = "small",
        telegramUserId: String? = null,
        fileBytes: ByteArray = ByteArray(8),
        fileName: String = "audio.m4a",
    ): ResponseEntity<Map<*, *>> {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"stub-job"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/stub-job"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val file = object : ByteArrayResource(fileBytes) {
            override fun getFilename() = fileName
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            if (telegramUserId != null) set("X-Telegram-User-Id", telegramUserId)
        }
        @Suppress("UNCHECKED_CAST")
        return rest.postForEntity(
            "/transcribe?model=$model&diarize=false&summary_mode=off",
            HttpEntity(body, headers),
            Map::class.java,
        ) as ResponseEntity<Map<*, *>>
    }

    private fun submitAndGetSubmission(model: String = "small", telegramUserId: String? = null): SubmissionEntity {
        val response = submit(model = model, telegramUserId = telegramUserId)
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val jobId = UUID.fromString(response.body!!["job_id"] as String)
        return submissionRepo.findById(jobId).orElseThrow()
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `valid submit returns 202 with job_id`() {
        val response = submit()
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(response.body!!["job_id"]).isNotNull()
    }

    @Test
    fun `model alias fast is normalized to small`() {
        assertThat(submitAndGetSubmission(model = "fast").model).isEqualTo("small")
    }

    @Test
    fun `model alias accurate is normalized to large-v3`() {
        assertThat(submitAndGetSubmission(model = "accurate").model).isEqualTo("large-v3")
    }

    @Test
    fun `raw model name passes through unchanged`() {
        assertThat(submitAndGetSubmission(model = "large-v3").model).isEqualTo("large-v3")
    }

    @Test
    fun `telegram header sets source to telegram`() {
        assertThat(submitAndGetSubmission(telegramUserId = "99999").source).isEqualTo("telegram")
    }

    @Test
    fun `no telegram header sets source to web`() {
        assertThat(submitAndGetSubmission().source).isEqualTo("web")
    }

    @Test
    fun `unsupported file extension is rejected with 400`() {
        val response = submit(fileName = "document.txt")
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `file with jpeg magic bytes is rejected with 400`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16)
        val response = submit(fileBytes = jpegHeader, fileName = "audio.m4a")
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
