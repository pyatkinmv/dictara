package com.dictara.gateway

import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.port.TokenUsage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one-off backlog run (`POST /admin/reanalyze`): it asks Gemini again about the transcriptions it is given, one by one,
 * replaces their summaries and refreshes their unsettled proposals, and it is built not to spend money by accident.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [
        "spring.main.allow-bean-definition-overriding=true",
        "dictara.admin.key=test-admin-key",
        "dictara.admin.reanalyze-pause-ms=0",
        "dictara.admin.stop-after-failures=3",
        "dictara.summarizer.retry-pause-ms=1",
    ],
)
@Import(BacklogReanalysisIntegrationTest.ControlledSummarizer::class)
class BacklogReanalysisIntegrationTest : AbstractSharedContextIntegrationTest() {

    @TestConfiguration
    class ControlledSummarizer {
        @Bean @Primary
        fun summarizer(): SummarizerPort = object : SummarizerPort {
            override fun isAvailable() = true
            override fun analyze(input: AnalysisInput): Analysis {
                asked.add(input)
                gate?.await(10, TimeUnit.SECONDS)
                if (input.text.contains("FAILME")) throw RuntimeException("Gemini error (503)")
                if (failEverything) throw RuntimeException("Gemini error (429)")
                return Analysis("Reanalysed.", title = "Новый заголовок", usage = TokenUsage(1000, 200, 800))
            }
        }
    }

    companion object {
        @Volatile var failEverything = false
        @Volatile var gate: CountDownLatch? = null
        val asked = CopyOnWriteArrayList<AnalysisInput>()
    }

    @Autowired lateinit var rest: TestRestTemplate

    private val json = ObjectMapper()
    private val key = "test-admin-key"
    private lateinit var owner: UUID

    @BeforeEach
    fun setUp() {
        failEverything = false
        gate = null
        asked.clear()
        owner = newUser()
        // A batch of an earlier test may still be finishing: wait for it, so that it cannot touch this one.
        val deadline = System.currentTimeMillis() + 15_000
        while (statusOrNull()?.get("state")?.asText() == "running") {
            check(System.currentTimeMillis() < deadline) { "an earlier batch is still running" }
            Thread.sleep(50)
        }
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private fun addTranscription(user: UUID = owner, status: String = "done", text: String = "Привет, это встреча про бюджет.", withSummary: Boolean = true): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.mp3', 'audio/mp4', 1000, ?, ?)",
            audioId, user, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, 'off', 'web', ?)",
            submissionId, user, audioId, status,
        )
        insertTranscript(UUID.randomUUID(), submissionId, """[{"start": 0, "end": 5, "text": "$text", "speaker": "SPEAKER_00"}]""")
        if (withSummary) jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, 'The old summary.')", submissionId)
        return submissionId
    }

    private class Reply(val status: HttpStatus, val body: JsonNode?)

    private fun call(method: HttpMethod, path: String, body: String? = null, adminKey: String? = key): Reply {
        val headers = HttpHeaders().apply {
            adminKey?.let { set("X-Admin-Key", it) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity<String>(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(HttpStatus.valueOf(response.statusCode.value()), text?.let(json::readTree))
    }

    private fun reanalyze(ids: List<Any>, user: UUID = owner, dryRun: Boolean = false, force: Boolean = false, adminKey: String? = key): Reply {
        val list = ids.joinToString(",") { "\"$it\"" }
        return call(HttpMethod.POST, "/admin/reanalyze", """{"user_id": "$user", "ids": [$list], "dry_run": $dryRun, "force": $force}""", adminKey)
    }

    private fun statusOrNull(): JsonNode? = call(HttpMethod.GET, "/admin/reanalyze/status").body?.takeIf { it.has("state") }

    private fun waitForEnd(): JsonNode {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val s = statusOrNull()
            if (s != null && s["state"].asText() != "running") return s
            Thread.sleep(50)
        }
        error("The batch did not end")
    }

    private fun summaryOf(id: UUID): String = jdbcTemplate.queryForObject("SELECT text FROM summaries WHERE submission_id = ?", String::class.java, id)!!
    private fun row(id: UUID): Map<String, Any?> = jdbcTemplate.queryForMap(
        "SELECT summary_regeneration, summary_regenerations, analysis_backfilled_at FROM submissions WHERE id = ?", id,
    )

    // ── The key ────────────────────────────────────────────────────────────────

    @Test
    fun `without the right key nothing is done and nothing is said`() {
        val id = addTranscription()

        assertThat(reanalyze(listOf(id), adminKey = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(reanalyze(listOf(id), adminKey = "wrong").status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.GET, "/admin/reanalyze/status", adminKey = "wrong").status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.POST, "/admin/reanalyze/stop", adminKey = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(asked).isEmpty()
        assertThat(summaryOf(id)).isEqualTo("The old summary.")
    }

    // ── What would happen: the dry run, and what is left out ───────────────────

    @Test
    fun `a dry run says what would be done and what it would cost, and asks nothing`() {
        val ok1 = addTranscription()
        val ok2 = addTranscription()

        val reply = reanalyze(listOf(ok1, ok2), dryRun = true)

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!["started"].asBoolean()).isFalse()
        val plan = reply.body!!["plan"]
        assertThat(plan["eligible"].map { it.asText() }).containsExactly(ok1.toString(), ok2.toString())
        assertThat(plan["estimated_input_tokens"].asLong()).isGreaterThan(1_000)
        assertThat(plan["estimated_cost_usd"].asDouble()).isGreaterThan(0.0)
        assertThat(asked).isEmpty()
        assertThat(summaryOf(ok1)).isEqualTo("The old summary.")
        assertThat(statusOrNull()).isNull() // no batch was started
    }

    @Test
    fun `ids that are not the user's, not found, not ready, repeated or not even ids are left out with the reason`() {
        val ok = addTranscription()
        val someoneElses = addTranscription(user = newUser())
        val notReady = addTranscription(status = "processing")
        val missing = UUID.randomUUID()

        val plan = reanalyze(listOf(ok, ok, someoneElses, notReady, missing, "not-an-id"), dryRun = true).body!!["plan"]

        assertThat(plan["eligible"].map { it.asText() }).containsExactly(ok.toString())
        val skipped = plan["skipped"]
        assertThat(skipped["duplicate"].map { it.asText() }).containsExactly(ok.toString())
        assertThat(skipped["not_owner"].map { it.asText() }).containsExactly(someoneElses.toString())
        assertThat(skipped["not_ready"].map { it.asText() }).containsExactly(notReady.toString())
        assertThat(skipped["not_found"].map { it.asText() }).containsExactly(missing.toString())
        assertThat(skipped["invalid"].map { it.asText() }).containsExactly("not-an-id")
    }

    @Test
    fun `a call needs a user, ids, and not too many`() {
        val id = addTranscription()

        assertThat(call(HttpMethod.POST, "/admin/reanalyze", """{"ids": ["$id"]}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(call(HttpMethod.POST, "/admin/reanalyze", """{"user_id": "$owner", "ids": []}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        val tooMany = List(301) { UUID.randomUUID() }
        assertThat(reanalyze(tooMany).status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(asked).isEmpty()
    }

    // ── A run ──────────────────────────────────────────────────────────────────

    @Test
    fun `a run replaces the summaries, marks them, and leaves the user's own regenerate button as it was`() {
        val ids = List(3) { addTranscription() }

        val reply = reanalyze(ids)
        assertThat(reply.status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(reply.body!!["started"].asBoolean()).isTrue()
        val status = waitForEnd()

        assertThat(status["state"].asText()).isEqualTo("finished")
        assertThat(status["total"].asInt()).isEqualTo(3)
        assertThat(status["done"].asInt()).isEqualTo(3)
        assertThat(status["failed"].asInt()).isZero()
        for (id in ids) {
            assertThat(summaryOf(id)).isEqualTo("Reanalysed.")
            val r = row(id)
            assertThat(r["analysis_backfilled_at"]).isNotNull()
            assertThat(r["summary_regenerations"]).isEqualTo(0) // not the user's regeneration
            assertThat(r["summary_regeneration"]).isNull()      // and nothing is left running
        }
        assertThat(asked).hasSize(3)
    }

    @Test
    fun `what it used and what that cost is reported`() {
        val ids = List(2) { addTranscription() }

        reanalyze(ids)
        val status = waitForEnd()

        assertThat(status["tokens_in"].asLong()).isEqualTo(2000)
        assertThat(status["tokens_out"].asLong()).isEqualTo(400)
        assertThat(status["tokens_thinking"].asLong()).isEqualTo(1600)
        // 2000 tokens in at $0.30 and 2000 out (the thinking is billed as output) at $2.50, per million.
        assertThat(status["cost_usd"].asDouble()).isEqualTo(0.0056)
    }

    @Test
    fun `the same ids sent again are not paid for again, unless asked for`() {
        val ids = List(2) { addTranscription() }
        reanalyze(ids)
        waitForEnd()
        assertThat(asked).hasSize(2)

        val again = reanalyze(ids)
        assertThat(again.status).isEqualTo(HttpStatus.CONFLICT) // nothing eligible: all already done
        assertThat(reanalyze(ids, dryRun = true).body!!["plan"]["skipped"]["already_done"]).hasSize(2)
        assertThat(asked).hasSize(2)

        assertThat(reanalyze(ids, force = true).status).isEqualTo(HttpStatus.ACCEPTED)
        waitForEnd()
        assertThat(asked).hasSize(4)
    }

    @Test
    fun `the proposals the user has settled stay, the waiting ones are replaced, as in the user's own regenerate`() {
        val id = addTranscription()
        val transcript = jdbcTemplate.queryForObject("SELECT id FROM transcripts WHERE submission_id = ?", UUID::class.java, id)
        jdbcTemplate.update("INSERT INTO transcript_suggestions (transcript_id, kind, title) VALUES (?, 'title', 'Старый заголовок')", transcript)

        reanalyze(listOf(id))
        waitForEnd()

        assertThat(jdbcTemplate.queryForList("SELECT title FROM transcript_suggestions WHERE transcript_id = ? AND kind = 'title' AND status = 'pending'", String::class.java, transcript))
            .containsExactly("Новый заголовок")
    }

    @Test
    fun `a transcription the user is regenerating at that moment is left alone`() {
        val busy = addTranscription()
        val free = addTranscription()
        jdbcTemplate.update("UPDATE submissions SET summary_regeneration = 'running' WHERE id = ?", busy)

        reanalyze(listOf(busy, free))
        val status = waitForEnd()

        assertThat(status["done"].asInt()).isEqualTo(1)
        assertThat(status["skipped_while_running"].asInt()).isEqualTo(1)
        assertThat(summaryOf(busy)).isEqualTo("The old summary.")
        assertThat(row(busy)["summary_regeneration"]).isEqualTo("running") // still the user's
        assertThat(row(busy)["analysis_backfilled_at"]).isNull()
    }

    // ── When something goes wrong ──────────────────────────────────────────────

    @Test
    fun `one failure does not stop the batch, and the transcription that failed is given back`() {
        val ok1 = addTranscription()
        val bad = addTranscription(text = "FAILME")
        val ok2 = addTranscription()

        reanalyze(listOf(ok1, bad, ok2))
        val status = waitForEnd()

        assertThat(status["state"].asText()).isEqualTo("finished")
        assertThat(status["done"].asInt()).isEqualTo(2)
        assertThat(status["failed"].asInt()).isEqualTo(1)
        assertThat(status["last_errors"][0].asText()).contains(bad.toString()).contains("Gemini error (503)")
        assertThat(summaryOf(bad)).isEqualTo("The old summary.")
        assertThat(row(bad)["analysis_backfilled_at"]).isNull()
        assertThat(row(bad)["summary_regeneration"]).isNull()
    }

    @Test
    fun `after a few failures in a row the batch stops by itself and the rest is not asked`() {
        failEverything = true
        val ids = List(6) { addTranscription() }

        reanalyze(ids)
        val status = waitForEnd()

        assertThat(status["state"].asText()).isEqualTo("stopped_after_failures")
        assertThat(status["failed"].asInt()).isEqualTo(3)
        assertThat(status["done"].asInt()).isZero()
        assertThat(asked).hasSize(9) // three transcriptions, three tries each; the other three were never asked
        for (id in ids) {
            assertThat(summaryOf(id)).isEqualTo("The old summary.")
            assertThat(row(id)["summary_regeneration"]).isNull()
        }
    }

    // ── Control ────────────────────────────────────────────────────────────────

    @Test
    fun `only one batch runs at a time, and it can be stopped after the transcription it is on`() {
        gate = CountDownLatch(1)
        val ids = List(4) { addTranscription() }
        reanalyze(ids)

        val deadline = System.currentTimeMillis() + 10_000
        while (asked.isEmpty()) { // the first transcription is now being asked about, held at the gate
            check(System.currentTimeMillis() < deadline) { "the batch did not start" }
            Thread.sleep(20)
        }
        assertThat(reanalyze(listOf(addTranscription())).status).isEqualTo(HttpStatus.CONFLICT) // while one runs
        assertThat(call(HttpMethod.POST, "/admin/reanalyze/stop").body!!["stopping"].asBoolean()).isTrue()
        gate!!.countDown()
        val status = waitForEnd()

        assertThat(status["state"].asText()).isEqualTo("stopped")
        assertThat(status["done"].asInt()).isEqualTo(1) // the one it was on
        assertThat(asked).hasSize(1)
        assertThat(ids.count { summaryOf(it) == "Reanalysed." }).isEqualTo(1)
    }

    @Test
    fun `stopping when nothing runs says so`() {
        assertThat(call(HttpMethod.POST, "/admin/reanalyze/stop").body!!["stopping"].asBoolean()).isFalse()
    }
}
