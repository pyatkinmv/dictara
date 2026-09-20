package com.dictara.gateway.local

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.containers.PostgreSQLContainer

/**
 * The whole `local` profile at once, against its own database (named like the development one, because
 * the profile refuses any other): no token needed, the seeded dev user, audio kept in the database,
 * and an upload that runs through the stub transcriber to `done`.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        // Quick stub, so the test does not wait for the demo timings.
        "dictara.local.stub.pending-seconds=0",
        "dictara.local.stub.transcribe-seconds=1",
        "dictara.local.stub.diarize-seconds=1",
        "dictara.transcriber.poll-interval-ms=100",
    ],
)
@ActiveProfiles("local")
class LocalProfileIntegrationTest {

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>("postgres:16").apply {
            withDatabaseName("dictara_dev")
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    private val json = ObjectMapper()

    private fun get(path: String): JsonNode {
        val response = rest.getForEntity(path, String::class.java)
        assertThat(response.statusCode).describedAs("GET $path").isEqualTo(HttpStatus.OK)
        return json.readTree(response.body)
    }

    private fun upload(fileName: String, bytes: ByteArray, diarize: Boolean = true): JsonNode {
        val file = object : ByteArrayResource(bytes) {
            override fun getFilename() = fileName
        }
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", file)
            add("diarize", diarize.toString())
        }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }
        val response = rest.postForEntity("/transcribe", HttpEntity(body, headers), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        return json.readTree(response.body)
    }

    private fun waitUntilDone(jobId: String): JsonNode {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val job = get("/jobs/$jobId")
            if (job["status"].asText() == "done") return job
            assertThat(job["status"].asText()).isIn("pending", "processing", "summarizing")
            Thread.sleep(200)
        }
        throw AssertionError("Job $jobId did not finish in time")
    }

    @Test
    fun `the seed creates the dev user on the unlimited plan`() {
        val plan = jdbc.queryForObject(
            "SELECT plan FROM users WHERE id = '00000000-0000-4000-8000-000000000001'", String::class.java,
        )
        assertThat(plan).isEqualTo("unlimited")
    }

    @Test
    fun `the local tables exist and the health endpoint answers`() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM local_audio_objects", Long::class.java)).isNotNull()
        assertThat(get("/health")["status"].asText()).isEqualTo("ok")
    }

    @Test
    fun `an upload goes through the stub transcriber to done and shows up for the dev user without a token`() {
        val bytes = ByteArray(64) { (it * 7 + 3).toByte() }

        val submitted = upload("weekly_call.mp3", bytes)
        val jobId = submitted["job_id"].asText()
        val job = waitUntilDone(jobId)

        val segments = job["result"]["segments"]
        assertThat(segments.size()).isGreaterThan(2)
        assertThat(segments.map { it["speaker"].asText() }).allMatch { it.startsWith("SPEAKER_") }
        assertThat(job["result"]["audio_duration_s"].asDouble()).isGreaterThan(0.0)
        assertThat(job["result"]["detected_language"].asText()).isIn("en", "ru")

        // The list needs a user; there is no token, so it is the dev user.
        val list = get("/transcriptions")
        val entry = list.first { it["job_id"].asText() == jobId }
        assertThat(entry["file_name"].asText()).isEqualTo("weekly_call.mp3")
        assertThat(entry["status"].asText()).isEqualTo("done")

        // The audio is in the database, under a db:// reference.
        val uris = jdbc.queryForList("SELECT uri FROM local_audio_objects", String::class.java)
        assertThat(uris).anyMatch { it.startsWith("db://audio/") && it.endsWith("/weekly_call.mp3") }
        val owner = jdbc.queryForObject("SELECT user_id::text FROM submissions WHERE id = ?::uuid", String::class.java, jobId)
        assertThat(owner).isEqualTo("00000000-0000-4000-8000-000000000001")
    }

    @Test
    fun `the same file uploaded again is recognised as a duplicate`() {
        val bytes = ByteArray(64) { (it * 11 + 5).toByte() }

        val first = upload("standup.mp3", bytes)
        val second = upload("standup.mp3", bytes)

        assertThat(first["dedup"].asBoolean()).isFalse()
        assertThat(second["dedup"].asBoolean()).isTrue()
        assertThat(second["job_id"].asText()).isEqualTo(first["job_id"].asText())
    }

    @Test
    fun `a file with an unsupported extension is still rejected`() {
        val file = object : ByteArrayResource(ByteArray(8)) {
            override fun getFilename() = "notes.txt"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }

        val response = rest.postForEntity("/transcribe", HttpEntity(body, headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
