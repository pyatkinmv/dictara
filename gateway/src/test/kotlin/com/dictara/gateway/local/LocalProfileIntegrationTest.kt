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
import org.springframework.http.HttpMethod
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
        "dictara.local.stub.summarize-seconds=0.2",
        "dictara.local.stub.login-confirm-seconds=0.2",
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
    fun `under the local profile a token still opens MCP and nothing else does, though requests without a token are the dev user`() {
        // The convenience of this profile (no token means the seeded dev user) is for the app's own API, never for MCP.
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val none = rest.postForEntity("/mcp", HttpEntity(ping, headers), String::class.java)
        assertThat(none.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val created = json.readTree(rest.postForEntity("/mcp-tokens", HttpEntity("""{"name":"local"}""", headers), String::class.java).body)
        val secret = created["token"].asText()
        val withToken = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; setBearerAuth(secret) }
        val answer = rest.postForEntity("/mcp", HttpEntity(ping, withToken), String::class.java)
        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)

        val tools = json.readTree(
            rest.postForEntity("/mcp", HttpEntity("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"search_transcriptions","arguments":{"size":50}}}""", withToken), String::class.java).body,
        )
        val text = json.readTree(tools["result"]["content"][0]["text"].asText())
        assertThat(text["items"].map { it["title"].asText() }).contains("Quarterly Review Meeting") // the seeded data of the dev user
    }

    @Test
    fun `the seed creates the dev user on the unlimited plan`() {
        val plan = jdbc.queryForObject(
            "SELECT plan FROM users WHERE id = '00000000-0000-4000-8000-000000000001'", String::class.java,
        )
        assertThat(plan).isEqualTo("unlimited")
    }

    @Test
    fun `the seed puts the design's transcriptions into the list, with lengths, speakers and tags`() {
        val list = get("/transcriptions?page=0&size=50")["items"]
        val byName = list.associateBy { it["file_name"].asText() }

        val quarterly = byName["quarterly_review_2025-06-22.m4a"]!!
        assertThat(quarterly["title"].asText()).isEqualTo("Quarterly Review Meeting")
        assertThat(quarterly["status"].asText()).isEqualTo("done")
        assertThat(quarterly["audio_duration_s"].asDouble()).isEqualTo(862.0)
        assertThat(quarterly["speaker_count"].asInt()).isEqualTo(3)
        assertThat(quarterly["tags"].map { it["label"].asText() }).containsExactly("meeting", "quarterly")

        assertThat(byName["interview_anna_k.mp3"]!!["speaker_count"].asInt()).isEqualTo(2)
        assertThat(byName["interview_anna_k.mp3"]!!["tags"].map { it["label"].asText() }).containsExactly("hiring", "interview")
        assertThat(byName["voice_memo_2025-06-20.m4a"]!!["speaker_count"].asInt()).isZero()
        assertThat(byName["standup_2025-06-19.mp3"]!!["audio_duration_s"].asDouble()).isEqualTo(480.0)
        // No title of its own: shown under the file name.
        assertThat(byName["product_sync_2025_06_23.mp4"]!!["title"].asText()).isEqualTo("product_sync_2025_06_23.mp4")
        assertThat(byName["product_sync_2025_06_23.mp4"]!!["tags"].size()).isZero()
        // One failure, with nothing but the file behind it.
        val failed = byName["broken_recording.mp3"]!!
        assertThat(failed["status"].asText()).isEqualTo("failed")
        assertThat(failed.has("audio_duration_s")).isFalse()
        assertThat(failed.has("speaker_count")).isFalse()
    }

    @Test
    fun `the seed has recording dates for some transcriptions and none for the rest`() {
        val byName = get("/transcriptions?page=0&size=50")["items"].associateBy { it["file_name"].asText() }

        assertThat(byName["quarterly_review_2025-06-22.m4a"]!!["recorded_at"].asText()).startsWith("2025-06-20T09:12")
        assertThat(byName["interview_anna_k.mp3"]!!["recorded_at"].asText()).startsWith("2025-03-04T10:30")
        // Unknown is left out, not sent as null.
        assertThat(byName["voice_memo_2025-06-20.m4a"]!!.has("recorded_at")).isFalse()
        assertThat(byName["broken_recording.mp3"]!!.has("recorded_at")).isFalse()
    }

    @Test
    fun `the seed gives the speaker library of the design, with usage from the voices`() {
        val speakers = get("/speakers")["speakers"].associateBy { it["name"].asText() }

        assertThat(speakers.keys).containsExactly("Alexei Sorokin", "Maria Chen", "James Wright") // in the design's order
        assertThat(speakers["Maria Chen"]!!["description"].asText()).startsWith("Head of Engineering")
        // Alexei and Maria are in the quarterly review (3 voices), the interview (2 voices) and the standup (3);
        // James only in the two with three voices.
        assertThat(speakers["Alexei Sorokin"]!!["transcription_count"].asInt()).isEqualTo(3)
        assertThat(speakers["James Wright"]!!["transcription_count"].asInt()).isEqualTo(2)
        assertThat(speakers["James Wright"]!!["speaking_s"].asDouble()).isGreaterThan(0.0)
        assertThat(speakers["Alexei Sorokin"]!!["sample_titles"].map { it.asText() }).containsExactly("Quarterly Review Meeting", "Interview: Anna K.") // the two newest
    }

    @Test
    fun `the seed proposes a title, a tag and three speakers for the product sync`() {
        val s = get("/transcriptions/00000000-0000-4000-8000-0000000b0006")["suggestions"]

        assertThat(s["title"].asText()).isEqualTo("Product Sync — Q3 Roadmap")
        assertThat(s["tags"].map { it["label"].asText() }).containsExactly("meeting")
        assertThat(s["speakers"].map { it["voice"].asText() to it["name"].asText() })
            .containsExactly("SPEAKER_00" to "Alexei Sorokin", "SPEAKER_01" to "Maria Chen", "SPEAKER_02" to "Peter Novak")
        assertThat(s["speakers"].map { it.has("speaker_id") }).containsExactly(true, true, false) // Peter is not in the library
    }

    @Test
    fun `the seed can be applied again without changing anything`() {
        fun counts() = listOf("users", "audio_meta", "submissions", "transcripts", "tags", "transcript_tags", "speakers", "transcript_voices", "transcript_segments").map {
            jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)
        }
        val before = counts()

        val seed = org.springframework.core.io.ClassPathResource("db/local/R__seed.sql").inputStream.readBytes().decodeToString()
        jdbc.execute("BEGIN; $seed COMMIT;")

        assertThat(counts()).isEqualTo(before)
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

        // The stub transcriber's fake audio_metadata (standing in for a real ffprobe answer) reached the DB.
        val metadataJson = jdbc.queryForObject(
            "SELECT am.metadata::text FROM audio_meta am JOIN submissions s ON s.audio_id = am.id WHERE s.id = ?::uuid",
            String::class.java, jobId,
        )
        assertThat(metadataJson).contains("creation_time")
    }

    @Test
    fun `an upload from the web client goes through the stubs to done, with a Markdown summary`() {
        val file = object : ByteArrayResource(ByteArray(64) { (it * 13 + 1).toByte() }) {
            override fun getFilename() = "web_upload.mp3"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }

        val response = rest.postForEntity("/transcriptions?summary=full&diarize=true&num_speakers=2", HttpEntity(body, headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val jobId = json.readTree(response.body)["item"]["job_id"].asText()
        waitUntilDone(jobId)
        val detail = get("/transcriptions/$jobId")
        assertThat(detail["status"].asText()).isEqualTo("done")
        assertThat(detail["summary"].asText()).contains("stub summary").contains("**Key points**")
        assertThat(detail["segments"].map { it["speaker"].asText() }.toSet()).hasSizeBetween(1, 2) // at most the two voices asked for
        // The stub proposes what its rules find; the title is always there, and a tag because the seed has a library.
        assertThat(detail["suggestions"]["title"].asText()).isNotBlank()
        assertThat(detail["suggestions"]["tags"]).isNotEmpty()
    }

    @Test
    fun `summary off produces no summary`() {
        val file = object : ByteArrayResource(ByteArray(64) { (it * 17 + 9).toByte() }) {
            override fun getFilename() = "no_summary.mp3"
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", file) }
        val headers = HttpHeaders().apply { contentType = MediaType.MULTIPART_FORM_DATA }

        val response = rest.postForEntity("/transcriptions?summary=off", HttpEntity(body, headers), String::class.java)

        val jobId = json.readTree(response.body)["item"]["job_id"].asText()
        waitUntilDone(jobId)
        assertThat(get("/transcriptions/$jobId").has("summary")).isFalse()
        assertThat(get("/transcriptions/$jobId").has("suggestions")).isFalse() // off switches the AI off altogether
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

    // ── The local Telegram bot stub (LocalTelegramBotSimulator) ──────────────────────────────────────────

    private fun postJson(path: String, body: String): JsonNode {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response = rest.postForEntity(path, HttpEntity(body, headers), String::class.java)
        assertThat(response.statusCode).describedAs("POST $path").isEqualTo(HttpStatus.OK)
        return json.readTree(response.body)
    }

    private fun loginByUsername(username: String) = postJson("/auth/login-by-username", """{"telegram_username": "$username"}""")

    private fun waitConfirmed(token: String): JsonNode {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val status = get("/auth/login-link/$token")
            if (status["confirmed"].asBoolean()) return status
            Thread.sleep(100)
        }
        throw AssertionError("Login $token was not confirmed in time")
    }

    @Test
    fun `a username nobody has used before is auto-confirmed by the local bot stub`() {
        val start = loginByUsername("dev_${System.nanoTime()}")
        assertThat(start["status"].asText()).isEqualTo("notified")
        assertThat(get("/auth/login-link/${start["token"].asText()}")["confirmed"].asBoolean()).isFalse() // not yet — the stub waits a bit

        val confirmed = waitConfirmed(start["token"].asText())

        assertThat(confirmed["token"].asText()).isNotBlank() // the JWT
        assertThat(confirmed["display_name"].asText()).isNotBlank()
    }

    @Test
    fun `logging in again under the same name returns the same account`() {
        val username = "repeat_${System.nanoTime()}"
        val first = waitConfirmed(loginByUsername(username)["token"].asText())

        val second = waitConfirmed(loginByUsername(username)["token"].asText())

        assertThat(second["display_name"].asText()).isEqualTo(first["display_name"].asText())
        // Both JWTs decode to the same user: the seeded library shows up for either.
        val library1 = rest.exchange("/tags", HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(first["token"].asText()) }), String::class.java)
        val library2 = rest.exchange("/tags", HttpMethod.GET, HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(second["token"].asText()) }), String::class.java)
        assertThat(library1.body).isEqualTo(library2.body)
    }

    @Test
    fun `the username 'unknown' is left unregistered, so it exercises the 'not found' screen`() {
        val start = loginByUsername("unknown")

        assertThat(start["status"].asText()).isEqualTo("unknown_user")
        assertThat(start["bot_url"].asText()).contains("t.me")

        // The stub never auto-confirms an unknown_user attempt.
        Thread.sleep(500)
        assertThat(get("/auth/login-link/${start["token"].asText()}")["confirmed"].asBoolean()).isFalse()
    }

    @Test
    fun `different names are different, brand-new accounts`() {
        val a = waitConfirmed(loginByUsername("alice_${System.nanoTime()}")["token"].asText())
        val b = waitConfirmed(loginByUsername("bob_${System.nanoTime()}")["token"].asText())

        assertThat(a["display_name"].asText()).isNotEqualTo(b["display_name"].asText())
    }
}
