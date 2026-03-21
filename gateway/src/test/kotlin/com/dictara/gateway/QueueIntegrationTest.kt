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
class QueueIntegrationTest {

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
        val jobA = submitFile(1001L, "q-job-a")
        val jobB = submitFile(1002L, "q-job-b")
        val jobC = submitFile(1003L, "q-job-c")

        waitForStatus(jobA, "processing")
        waitForStatus(jobB, "processing")
        waitForStatus(jobC, "processing")

        assertThat(queuePosition(jobA)).isEqualTo(1)
        assertThat(queuePosition(jobB)).isEqualTo(2)
        assertThat(queuePosition(jobC)).isEqualTo(3)
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
    fun `waiting jobs are renumbered when earlier job is actively transcribed`() {
        // Submit jobs one at a time so each gets its own transcriber job ID stub
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-multi-a"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-multi-a"))
            .willReturn(okJson("""{"status":"processing","progress":{"phase":"transcribing","processed_s":10.0,"total_s":100.0}}""")))
        val jobA = submitFileRaw(6001L)
        waitForStatus(jobA, "processing")

        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-multi-b"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-multi-b"))
            .willReturn(okJson("""{"status":"processing"}""")))
        val jobB = submitFileRaw(6002L)
        waitForStatus(jobB, "processing")

        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"q-multi-c"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/q-multi-c"))
            .willReturn(okJson("""{"status":"processing"}""")))
        val jobC = submitFileRaw(6003L)
        waitForStatus(jobC, "processing")

        waitForProgress(jobA)

        assertThat(queuePosition(jobA)).isNull()
        val posB = queuePosition(jobB)!!
        val posC = queuePosition(jobC)!!
        assertThat(posB).isLessThan(posC)
        assertThat(posC - posB).isEqualTo(1)
    }
}
