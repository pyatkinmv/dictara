package com.dictara.gateway

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
import javax.sql.DataSource

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class QueueIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanDb() {
        dataSource.connection.use { conn ->
            conn.createStatement().execute(
                "TRUNCATE TABLE stage_attempts, telegram_deliveries, submission_tags, " +
                "diarizations, summaries, transcripts, audio_content, audio_meta, submissions CASCADE"
            )
        }
    }

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

    private fun submitFile(chatId: Long, jobId: String): UUID {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"$jobId"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/$jobId"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val fakeAudio = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "audio.m4a"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", chatId.toString())
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=off",
            HttpEntity(body, headers), Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        return UUID.fromString(response.body!!["job_id"] as String)
    }

    private fun waitForStatus(jobId: UUID, targetStatus: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val resp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
            if (resp["status"] == targetStatus) return
            Thread.sleep(100)
        }
        error("Job $jobId did not reach status=$targetStatus within timeout")
    }

    private fun queuePosition(jobId: UUID): Int? {
        val resp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
        return resp["queue_position"] as Int?
    }

    private fun submitFileRaw(chatId: Long): UUID {
        val fakeAudio = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "audio.m4a"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", chatId.toString())
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=off",
            HttpEntity(body, headers), Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        return UUID.fromString(response.body!!["job_id"] as String)
    }

    private fun waitForProgress(jobId: UUID) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val resp = rest.getForEntity("/jobs/$jobId", Map::class.java).body!!
            if (resp["progress"] != null) return
            Thread.sleep(100)
        }
        error("Job $jobId did not get progress data within timeout")
    }

    @Test
    fun `queue position reflects order of submission among active jobs`() {
        // A gets dispatched immediately; B and C stay pending (only one job runs at a time)
        val jobA = submitFile(1001L, "q-job-a")
        val jobB = submitFileRaw(1002L)
        val jobC = submitFileRaw(1003L)

        waitForStatus(jobA, "processing")

        // A is being transcribed — no queue position
        assertThat(queuePosition(jobA)).isNull()
        // B and C are pending — queue positions 1 and 2
        assertThat(queuePosition(jobB)).isEqualTo(1)
        assertThat(queuePosition(jobC)).isEqualTo(2)
    }

    @Test
    fun `queue position is null for completed jobs`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-done-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-done-1"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,"result":{"formatted_text":"Hi.","audio_duration_s":2.0,"segments":[]}}""")))

        val fakeAudio = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "audio.m4a"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-Chat-Id", "2001")
        }
        val response = rest.postForEntity(
            "/transcribe?model=fast&diarize=false&summary_mode=off",
            HttpEntity(body, headers), Map::class.java,
        )
        val jobId = UUID.fromString(response.body!!["job_id"] as String)
        waitForStatus(jobId, "done")

        assertThat(queuePosition(jobId)).isNull()
    }

    @Test
    fun `actively transcribed job has null queue position`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-active-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-active-1"))
            .willReturn(okJson("""{"status":"processing","progress":{"phase":"transcribing","processed_s":10.0,"total_s":100.0}}""")))

        val jobId = submitFileRaw(5001L)
        waitForProgress(jobId)

        assertThat(queuePosition(jobId)).isNull()
    }

    @Test
    fun `job B is dispatched after job A completes`() {
        // POST: first call dispatches A, second call dispatches B after A finishes
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .inScenario("seq").whenScenarioStateIs("Started")
            .willReturn(okJson("""{"job_id":"seq-a"}"""))
            .willSetStateTo("a-submitted"))
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .inScenario("seq").whenScenarioStateIs("a-submitted")
            .willReturn(okJson("""{"job_id":"seq-b"}""")))

        wireMock.stubFor(get(urlEqualTo("/jobs/seq-a"))
            .willReturn(okJson("""{"status":"done","duration_s":0.1,"result":{"formatted_text":"Hi","audio_duration_s":1.0,"segments":[]}}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/seq-b"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val jobA = submitFileRaw(7001L)
        val jobB = submitFileRaw(7002L)

        waitForStatus(jobA, "done")       // A completes
        waitForStatus(jobB, "processing") // B automatically dispatched afterwards

        assertThat(queuePosition(jobB)).isNull() // B is now processing, not in queue
    }

    @Test
    fun `waiting jobs are renumbered when earlier job is actively transcribed`() {
        // A: dispatched with progress data confirming active transcription
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-multi-a"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-multi-a"))
            .willReturn(okJson("""{"status":"processing","progress":{"phase":"transcribing","processed_s":10.0,"total_s":100.0}}""")))
        val jobA = submitFileRaw(6001L)

        // B and C: stay pending (A is already processing; only one job runs at a time)
        val jobB = submitFileRaw(6002L)
        val jobC = submitFileRaw(6003L)

        waitForProgress(jobA)  // A has live progress — confirmed actively transcribing

        assertThat(queuePosition(jobA)).isNull()
        val posB = queuePosition(jobB)!!
        val posC = queuePosition(jobC)!!
        assertThat(posB).isLessThan(posC)
        assertThat(posC - posB).isEqualTo(1)
    }
}
