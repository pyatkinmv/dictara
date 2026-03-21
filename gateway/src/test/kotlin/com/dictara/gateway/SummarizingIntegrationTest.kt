package com.dictara.gateway

import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.SummarizerPort
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.*
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@Import(SummarizingIntegrationTest.StubSummarizerConfig::class)
class SummarizingIntegrationTest {

    /** Replaces the real summarizer with a stub that reports available and sleeps briefly,
     *  making the 'summarizing' status window observable in tests. */
    @TestConfiguration
    class StubSummarizerConfig {
        @Bean @Primary
        fun summarizer(): SummarizerPort = object : SummarizerPort {
            override fun isAvailable() = true
            override fun summarize(text: String, audioDurationSeconds: Double?, mode: SummaryMode, language: String): String {
                Thread.sleep(500) // hold 'summarizing' state long enough to observe
                return "Test summary"
            }
        }
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var dataSource: DataSource

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

    @BeforeEach
    fun cleanDb() {
        dataSource.connection.use { conn ->
            conn.createStatement().execute(
                "TRUNCATE TABLE stage_attempts, telegram_deliveries, submission_tags, " +
                "diarizations, summaries, transcripts, audio_content, audio_meta, submissions CASCADE"
            )
        }
    }

    @Test
    fun `job transitions through summarizing before done and result includes summary`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"sum-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/sum-1"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,"result":{"formatted_text":"Hello","audio_duration_s":2.0,"segments":[]}}""")))

        val jobId = submitFile(3001L, summaryMode = "brief")

        waitForStatus(jobId, "summarizing")  // job must reach summarizing
        waitForStatus(jobId, "done")         // then complete

        val resp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
        @Suppress("UNCHECKED_CAST")
        val result = resp["result"] as Map<String, Any?>
        assertThat(result["summary"]).isEqualTo("Test summary")
    }

    @Test
    fun `next job is dispatched while previous is still summarizing`() {
        // POST: first call → sum-seq-a (for A), second call → sum-seq-b (for B dispatched during A's summarization)
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .inScenario("sum-seq").whenScenarioStateIs("Started")
            .willReturn(okJson("""{"job_id":"sum-seq-a"}"""))
            .willSetStateTo("second"))
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .inScenario("sum-seq").whenScenarioStateIs("second")
            .willReturn(okJson("""{"job_id":"sum-seq-b"}""")))

        wireMock.stubFor(get(urlEqualTo("/jobs/sum-seq-a"))
            .willReturn(okJson("""{"status":"done","duration_s":0.1,"result":{"formatted_text":"Hi","audio_duration_s":1.0,"segments":[]}}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/sum-seq-b"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val jobA = submitFile(3002L, summaryMode = "brief")
        val jobB = submitFile(3003L, summaryMode = "off")

        // dispatchNext() is called before summarization starts, so B should reach
        // processing while A is still in the 500ms summarization sleep
        waitForStatus(jobA, "summarizing")
        waitForStatus(jobB, "processing")
    }

    private fun submitFile(chatId: Long, summaryMode: String): UUID {
        val fakeAudio = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "audio.m4a"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", chatId.toString())
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=$summaryMode",
            HttpEntity(body, headers), Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        return UUID.fromString(response.body!!["job_id"] as String)
    }

    private fun waitForStatus(jobId: UUID, targetStatus: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val resp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
            if (resp["status"] == targetStatus) return
            Thread.sleep(100)
        }
        error("Job $jobId did not reach status=$targetStatus within timeout")
    }
}
