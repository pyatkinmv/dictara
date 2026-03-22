package com.dictara.gateway

import com.dictara.gateway.repository.TelegramDeliveryRepository
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
class TelegramDeliveryIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var telegramDeliveryRepo: TelegramDeliveryRepository

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

    private fun submit(jobStub: String, telegramChatId: Long? = null): UUID {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"$jobStub"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/$jobStub"))
            .willReturn(okJson("""{"status":"processing"}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            if (telegramChatId != null) set("X-Telegram-Chat-Id", telegramChatId.toString())
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
        error("Job $jobId did not reach $targetStatus")
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingDeliveries(): List<Map<String, Any?>> =
        rest.getForEntity("/telegram/pending-deliveries", List::class.java).body as List<Map<String, Any?>>

    @Test
    fun `submit with chat id creates delivery row`() {
        val jobId = submit("td-job-1", telegramChatId = 100L)
        val delivery = telegramDeliveryRepo.findById(jobId).orElse(null)
        assertThat(delivery).isNotNull
        assertThat(delivery.chatId).isEqualTo(100L)
        assertThat(delivery.telegramMessageId).isNull()
        assertThat(delivery.deliveredAt).isNull()
    }

    @Test
    fun `submit with message id stores it in delivery row`() {
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            set("X-Telegram-Chat-Id", "100")
            set("X-Telegram-Message-Id", "42")
        }
        wireMock.stubFor(post(urlPathEqualTo("/transcribe")).willReturn(okJson("""{"job_id":"td-msgid-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/td-msgid-1")).willReturn(okJson("""{"status":"processing"}""")))
        val jobId = UUID.fromString(
            rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java).body!!["job_id"] as String
        )
        val delivery = telegramDeliveryRepo.findById(jobId).orElseThrow()
        assertThat(delivery.telegramMessageId).isEqualTo(42)
    }

    @Test
    fun `submit without chat id creates no delivery row`() {
        val jobId = submit("td-job-2", telegramChatId = null)
        assertThat(telegramDeliveryRepo.findById(jobId)).isEmpty
    }

    @Test
    fun `pending deliveries returns done job with correct fields`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"td-done-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/td-done-1"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,"result":{"formatted_text":"Hi.","audio_duration_s":2.0,"segments":[]}}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            set("X-Telegram-Chat-Id", "200")
            set("X-Telegram-Message-Id", "77")
        }
        val jobId = UUID.fromString(
            rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java).body!!["job_id"] as String
        )
        waitForStatus(jobId, "done")

        val deliveries = pendingDeliveries()
        val entry = deliveries.find { it["job_id"] == jobId.toString() }
        assertThat(entry).isNotNull
        assertThat(entry!!["chat_id"]).isEqualTo(200)
        assertThat(entry["telegram_message_id"]).isEqualTo(77)
        assertThat(entry["status"]).isEqualTo("done")
        assertThat(entry["error"]).isNull()
    }

    @Test
    fun `pending deliveries does not return in-progress jobs`() {
        val jobId = submit("td-job-3", telegramChatId = 300L)
        waitForStatus(jobId, "processing")

        val deliveries = pendingDeliveries()
        assertThat(deliveries.none { it["job_id"] == jobId.toString() }).isTrue
    }

    @Test
    fun `ack removes job from pending deliveries`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"td-ack-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/td-ack-1"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,"result":{"formatted_text":"Ok.","audio_duration_s":1.0,"segments":[]}}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            set("X-Telegram-Chat-Id", "400")
        }
        val jobId = UUID.fromString(
            rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java).body!!["job_id"] as String
        )
        waitForStatus(jobId, "done")

        assertThat(pendingDeliveries().any { it["job_id"] == jobId.toString() }).isTrue

        val ackResp = rest.postForEntity("/telegram/deliveries/$jobId/ack", HttpEntity.EMPTY, Map::class.java)
        assertThat(ackResp.body!!["claimed"]).isEqualTo(true)

        assertThat(pendingDeliveries().none { it["job_id"] == jobId.toString() }).isTrue
        assertThat(telegramDeliveryRepo.findById(jobId).orElseThrow().deliveredAt).isNotNull
    }

    @Test
    fun `pending deliveries returns failed job with truncated error`() {
        val longError = "E".repeat(200)
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"td-fail-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/td-fail-1"))
            .willReturn(okJson("""{"status":"failed","error":"$longError","retryable":false}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            set("X-Telegram-Chat-Id", "500")
        }
        val jobId = UUID.fromString(
            rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java).body!!["job_id"] as String
        )
        waitForStatus(jobId, "failed")

        val deliveries = pendingDeliveries()
        val entry = deliveries.find { it["job_id"] == jobId.toString() }
        assertThat(entry).isNotNull
        assertThat(entry!!["status"]).isEqualTo("failed")
        assertThat((entry["error"] as String).length).isLessThanOrEqualTo(150)
    }

    @Test
    fun `ack is idempotent`() {
        wireMock.stubFor(post(urlPathEqualTo("/transcribe"))
            .willReturn(okJson("""{"job_id":"td-idem-1"}""")))
        wireMock.stubFor(get(urlEqualTo("/jobs/td-idem-1"))
            .willReturn(okJson("""{"status":"done","duration_s":1.0,"result":{"formatted_text":"Ok.","audio_duration_s":1.0,"segments":[]}}""")))

        val body = LinkedMultiValueMap<String, Any>().apply { add("file", fakeAudio()) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            set("X-Telegram-User-Id", "999")
            set("X-Telegram-Chat-Id", "600")
        }
        val jobId = UUID.fromString(
            rest.postForEntity("/transcribe?model=fast&diarize=false&summary_mode=off",
                HttpEntity(body, headers), Map::class.java).body!!["job_id"] as String
        )
        waitForStatus(jobId, "done")

        val first = rest.postForEntity("/telegram/deliveries/$jobId/ack", HttpEntity.EMPTY, Map::class.java)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(first.body!!["claimed"]).isEqualTo(true)

        repeat(2) {
            val subsequent = rest.postForEntity("/telegram/deliveries/$jobId/ack", HttpEntity.EMPTY, Map::class.java)
            assertThat(subsequent.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(subsequent.body!!["claimed"]).isEqualTo(false)
        }
        assertThat(telegramDeliveryRepo.findById(jobId).orElseThrow().deliveredAt).isNotNull
    }
}
