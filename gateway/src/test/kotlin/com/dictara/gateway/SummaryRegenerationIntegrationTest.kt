package com.dictara.gateway

import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.port.SpeakerGuess
import com.dictara.gateway.service.SuggestionService
import com.dictara.gateway.service.SummaryRegenerationService
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * "Regenerate summary" (`POST /transcriptions/{id}/regenerate-summary`): the transcript stays, the summary is
 * replaced, what the user has not settled is proposed again, and it can be done once.
 *
 * The transcription used here (Russian, no detected language, as an old one would be):
 * ```
 * 0 SPEAKER_00  Привет, меня зовут Анна, это встреча про бюджет.
 * 1 SPEAKER_01  Спасибо, Анна. Давайте начнем с бюджета.
 * 2 SPEAKER_02  Я Борис, отвечаю за финансы.
 * ```
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["spring.main.allow-bean-definition-overriding=true"])
@Import(SummaryRegenerationIntegrationTest.ControlledSummarizer::class)
class SummaryRegenerationIntegrationTest : AbstractSharedContextIntegrationTest() {

    /** A summarizer the test steers: what it answers, whether it fails, and a gate to hold a call open. */
    @TestConfiguration
    class ControlledSummarizer {
        @Bean @Primary
        fun summarizer(): SummarizerPort = object : SummarizerPort {
            override fun isAvailable() = available
            override fun analyze(input: AnalysisInput): Analysis {
                asked.add(input)
                gate?.await(10, TimeUnit.SECONDS)
                failWith?.let { throw it }
                return answer
            }
        }
    }

    companion object {
        @Volatile var available = true
        @Volatile var answer = Analysis("A new summary.", title = "Новый заголовок")
        @Volatile var failWith: RuntimeException? = null
        @Volatile var gate: CountDownLatch? = null
        val asked = CopyOnWriteArrayList<AnalysisInput>()
    }

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var service: SummaryRegenerationService
    @Autowired lateinit var suggestionService: SuggestionService

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private lateinit var submission: UUID
    private lateinit var transcript: UUID

    @BeforeEach
    fun setUp() {
        available = true
        answer = Analysis("A new summary.", title = "Новый заголовок")
        failWith = null
        gate = null
        asked.clear()
        userId = newUser()
        val t = addTranscription(userId)
        submission = t.first
        transcript = t.second
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private fun addTranscription(owner: UUID, status: String = "done", summaryMode: String = "auto", withSummary: Boolean = true): Pair<UUID, UUID> {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.mp3', 'audio/mp4', 1000, ?, ?)",
            audioId, owner, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, ?, 'web', ?)",
            submissionId, owner, audioId, summaryMode, status,
        )
        val lines = listOf(
            "SPEAKER_00" to "Привет, меня зовут Анна, это встреча про бюджет.",
            "SPEAKER_01" to "Спасибо, Анна. Давайте начнем с бюджета.",
            "SPEAKER_02" to "Я Борис, отвечаю за финансы.",
        )
        val phrases = lines.mapIndexed { i, (voice, text) ->
            """{"start": ${i * 10}, "end": ${i * 10 + 10}, "text": "$text", "speaker": "$voice"}"""
        }.joinToString(prefix = "[", postfix = "]")
        insertTranscript(transcriptId, submissionId, phrases)
        if (withSummary) jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, 'The old summary.')", submissionId)
        return submissionId to transcriptId
    }

    private class Reply(val status: HttpStatus, val body: JsonNode?)

    private fun call(method: HttpMethod, path: String, user: UUID? = userId, body: String? = null): Reply {
        val headers = HttpHeaders().apply {
            user?.let { setBearerAuth(jwt.issue(it)) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity<String>(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(HttpStatus.valueOf(response.statusCode.value()), text?.let(json::readTree))
    }

    private fun regenerate(tx: UUID = submission, user: UUID? = userId) = call(HttpMethod.POST, "/transcriptions/$tx/regenerate-summary", user)
    private fun detail(tx: UUID = submission) = call(HttpMethod.GET, "/transcriptions/$tx").body!!
    private fun regenState(tx: UUID = submission) = detail(tx)["summary_regeneration"]["state"].asText()

    private fun waitForState(state: String, tx: UUID = submission) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (regenState(tx) == state) return
            Thread.sleep(50)
        }
        error("Regeneration of $tx did not reach '$state' (is '${regenState(tx)}')")
    }

    private fun suggestionRows(): List<String> = jdbcTemplate.queryForList(
        "SELECT kind || ':' || status || ':' || COALESCE(title, speaker_name, tag_id::text) FROM transcript_suggestions WHERE transcript_id = ? ORDER BY 1",
        String::class.java, transcript,
    )

    // ── The result ─────────────────────────────────────────────────────────────

    @Test
    fun `a transcription starts with the button available`() {
        val state = detail()["summary_regeneration"]
        assertThat(state["state"].asText()).isEqualTo("available")
        assertThat(state["used"].asInt()).isZero()
        assertThat(state["max"].asInt()).isEqualTo(1) // the default plan, free, allows it once
        assertThat(state.has("at")).isFalse()
    }

    @Test
    fun `the summary is replaced, a new title is proposed, and the regeneration is used up`() {
        jdbcTemplate.update("INSERT INTO transcript_suggestions (transcript_id, kind, title) VALUES (?, 'title', 'Старый заголовок')", transcript)

        val reply = regenerate()
        assertThat(reply.status).isEqualTo(HttpStatus.ACCEPTED)
        waitForState("used")

        val d = detail()
        assertThat(d["summary"].asText()).isEqualTo("A new summary.")
        assertThat(d["suggestions"]["title"].asText()).isEqualTo("Новый заголовок")
        assertThat(d["summary_regeneration"]["at"].asText()).isNotBlank()
        assertThat(d["segments"].size()).isEqualTo(3) // the transcript itself is untouched
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM summaries WHERE submission_id = ?", Int::class.java, submission)).isEqualTo(1)
    }

    @Test
    fun `a transcription that had no summary gets one`() {
        val (noSummary, _) = addTranscription(userId, withSummary = false)

        assertThat(regenerate(noSummary).status).isEqualTo(HttpStatus.ACCEPTED)
        waitForState("used", noSummary)

        assertThat(detail(noSummary)["summary"].asText()).isEqualTo("A new summary.")
    }

    @Test
    fun `what the user settled stays, what they did not is proposed again`() {
        val rejectedTag = UUID.randomUUID()
        val pendingTag = UUID.randomUUID()
        val oldTag = UUID.randomUUID()
        listOf(rejectedTag to "работа", pendingTag to "бюджет", oldTag to "семья").forEach { (id, name) ->
            jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, ?)", id, userId, name)
        }
        jdbcTemplate.update("INSERT INTO transcript_suggestions (transcript_id, kind, tag_id, status) VALUES (?, 'tag', ?, 'rejected')", transcript, rejectedTag)
        jdbcTemplate.update("INSERT INTO transcript_suggestions (transcript_id, kind, tag_id) VALUES (?, 'tag', ?)", transcript, oldTag)
        jdbcTemplate.update("INSERT INTO transcript_suggestions (transcript_id, kind, title, status) VALUES (?, 'title', 'Принятый заголовок', 'accepted')", transcript)
        answer = Analysis("A new summary.", title = "Новый заголовок", tags = listOf("работа", "бюджет"))

        regenerate()
        waitForState("used")

        val rows = suggestionRows()
        assertThat(rows).contains("tag:rejected:$rejectedTag") // not proposed again
        assertThat(rows).contains("tag:pending:$pendingTag")   // a new proposal
        assertThat(rows).doesNotContain("tag:pending:$oldTag") // an old, unsettled one that is no longer proposed is gone
        assertThat(rows).contains("title:pending:Новый заголовок")
        assertThat(rows.filter { it.startsWith("title:") }).hasSize(1)
    }

    @Test
    fun `the model is asked in the language of the text, and a transcription made without a summary is asked as auto`() {
        val (off, _) = addTranscription(userId, summaryMode = "off")

        regenerate(off)
        waitForState("used", off)

        val input = asked.single()
        assertThat(input.language).isEqualTo("ru") // Russian text, no detected language: the alphabet decides
        assertThat(input.mode).isEqualTo(SummaryMode.AUTO)
        assertThat(input.text).contains("меня зовут Анна")
    }

    // ── On top of what the user has already decided ────────────────────────────

    private fun libraryTag(name: String, description: String? = null): UUID {
        val body = if (description == null) """{"label": "$name"}""" else """{"label": "$name", "description": "$description"}"""
        return UUID.fromString(call(HttpMethod.POST, "/tags", body = body).body!!["id"].asText())
    }

    private fun librarySpeaker(name: String, description: String? = null): UUID {
        val body = if (description == null) """{"name": "$name"}""" else """{"name": "$name", "description": "$description"}"""
        return UUID.fromString(call(HttpMethod.POST, "/speakers", body = body).body!!["id"].asText())
    }

    private fun settle(action: String) = call(HttpMethod.POST, "/transcriptions/$submission/suggestions/$action").status
    private fun pendingTags(): List<String> = detail()["suggestions"]?.get("tags")?.map { it["label"].asText() } ?: emptyList()
    private fun pendingSpeakers(): Map<String, String> =
        detail()["suggestions"]?.get("speakers")?.associate { it["voice"].asText() to it["name"].asText() } ?: emptyMap()
    private fun pendingTitle(): String? = detail()["suggestions"]?.get("title")?.asText()
    private fun attachedTags(): List<String> = detail()["tags"].map { it["label"].asText() }
    private fun namedVoices(): Map<String, String> = detail()["voices"].associate { it["voice"].asText() to it["speaker"]["name"].asText() }
    private fun transcriptTitle(): String? = jdbcTemplate.queryForObject("SELECT title FROM transcripts WHERE id = ?", String::class.java, transcript)
    private fun count(table: String): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM $table WHERE transcript_id = ?", Int::class.java, transcript)!!

    private fun regenerateWith(analysis: Analysis) {
        answer = analysis
        assertThat(regenerate().status).isEqualTo(HttpStatus.ACCEPTED)
        waitForState("used")
    }

    @Test
    fun `a transcription with accepted, rejected and waiting proposals keeps the decisions and gets fresh proposals for the rest`() {
        libraryTag("budget", "Budget talks")
        libraryTag("family")
        libraryTag("work")
        libraryTag("sport")
        librarySpeaker("Анна", "Head of finance")
        librarySpeaker("Борис")
        val anna = SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна")
        val boris = SpeakerGuess("SPEAKER_02", "Борис", "Я Борис")
        // First analysis, then the user decides: accepts a tag and a speaker, rejects a tag and a speaker, leaves the rest.
        suggestionService.record(submission, Analysis("First.", title = "Первый заголовок", tags = listOf("budget", "family", "work"), speakers = listOf(anna, boris)))
        assertThat(settle("tags/budget/accept")).isEqualTo(HttpStatus.OK)
        assertThat(settle("tags/family/reject")).isEqualTo(HttpStatus.OK)
        assertThat(settle("speakers/SPEAKER_00/accept")).isEqualTo(HttpStatus.OK)
        assertThat(settle("speakers/SPEAKER_02/reject")).isEqualTo(HttpStatus.OK)
        assertThat(attachedTags()).containsExactly("budget")
        assertThat(namedVoices()).containsEntry("SPEAKER_00", "Анна")
        assertThat(pendingTags()).containsExactly("work")
        assertThat(pendingTitle()).isEqualTo("Первый заголовок")
        val voicesBefore = count("transcript_voices")

        regenerateWith(Analysis(
            "A new summary.", title = "Второй заголовок",
            tags = listOf("budget", "family", "sport"), // accepted again, rejected again, and a new one
            speakers = listOf(anna, boris),             // an accepted voice again, a rejected voice again
        ))

        // What was decided stays decided.
        assertThat(attachedTags()).containsExactly("budget")
        assertThat(namedVoices()).containsOnlyKeys("SPEAKER_00")
        assertThat(count("transcript_voices")).isEqualTo(voicesBefore)
        // What was only waiting is replaced by the new proposals; the same tag, voice or title is not proposed twice.
        assertThat(pendingTags()).containsExactly("sport")
        assertThat(pendingSpeakers()).isEmpty()
        assertThat(pendingTitle()).isEqualTo("Второй заголовок")
        assertThat(detail()["summary"].asText()).isEqualTo("A new summary.")
        assertThat(detail()["title"].asText()).isEqualTo("a.mp3") // the title was never accepted, and regenerating does not rename
    }

    @Test
    fun `an accepted title stays the title, and a different new one is proposed`() {
        suggestionService.record(submission, Analysis("First.", title = "Принятый заголовок"))
        assertThat(settle("accept-all")).isEqualTo(HttpStatus.OK)
        assertThat(transcriptTitle()).isEqualTo("Принятый заголовок")

        regenerateWith(Analysis("A new summary.", title = "Другой заголовок"))

        assertThat(transcriptTitle()).isEqualTo("Принятый заголовок")
        assertThat(detail()["title"].asText()).isEqualTo("Принятый заголовок")
        assertThat(pendingTitle()).isEqualTo("Другой заголовок")
    }

    @Test
    fun `the title the user accepted is not proposed again when the model gives it again`() {
        suggestionService.record(submission, Analysis("First.", title = "Принятый заголовок"))
        settle("accept-all")

        regenerateWith(Analysis("A new summary.", title = "Принятый заголовок"))

        assertThat(pendingTitle()).isNull()
        assertThat(transcriptTitle()).isEqualTo("Принятый заголовок")
    }

    @Test
    fun `a rejected title is not offered again, whatever quotes and spaces are around it`() {
        suggestionService.record(submission, Analysis("First.", title = "Отклонённый заголовок"))
        settle("dismiss-all")
        assertThat(pendingTitle()).isNull()

        regenerateWith(Analysis("A new summary.", title = "  «Отклонённый заголовок»  "))

        assertThat(pendingTitle()).isNull()
    }

    @Test
    fun `after a rejected title a different one is proposed`() {
        suggestionService.record(submission, Analysis("First.", title = "Отклонённый заголовок"))
        settle("dismiss-all")

        regenerateWith(Analysis("A new summary.", title = "Совсем другой заголовок"))

        assertThat(pendingTitle()).isEqualTo("Совсем другой заголовок")
    }

    @Test
    fun `a tag the user put on by hand is not proposed, and stays`() {
        libraryTag("budget")
        assertThat(call(HttpMethod.POST, "/transcriptions/$submission/tags", body = """{"label": "budget"}""").status.is2xxSuccessful).isTrue()

        regenerateWith(Analysis("A new summary.", tags = listOf("budget")))

        assertThat(attachedTags()).containsExactly("budget")
        assertThat(pendingTags()).isEmpty()
    }

    @Test
    fun `a voice that is already named is left alone, and the library and the phrases are not touched`() {
        librarySpeaker("Анна")
        suggestionService.record(submission, Analysis("First.", speakers = listOf(SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна"))))
        settle("speakers/SPEAKER_00/accept")
        val before = listOf(count("transcript_voices"), count("transcript_speakers"))

        regenerateWith(Analysis("A new summary.", speakers = listOf(
            SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна"),
            SpeakerGuess("SPEAKER_01", "Анна", "Спасибо, Анна"),
        )))

        assertThat(listOf(count("transcript_voices"), count("transcript_speakers"))).isEqualTo(before)
        assertThat(namedVoices()).isEqualTo(mapOf("SPEAKER_00" to "Анна"))
        assertThat(pendingSpeakers().keys).doesNotContain("SPEAKER_00") // the named voice is not asked about again
    }

    @Test
    fun `a waiting speaker proposal is replaced by what the model says now`() {
        librarySpeaker("Анна")
        suggestionService.record(submission, Analysis("First.", speakers = listOf(SpeakerGuess("SPEAKER_01", "Анна", "Спасибо, Анна"))))
        assertThat(pendingSpeakers()).containsEntry("SPEAKER_01", "Анна")

        regenerateWith(Analysis("A new summary.", speakers = listOf(SpeakerGuess("SPEAKER_02", "Борис", "Я Борис"))))

        assertThat(pendingSpeakers()).isEqualTo(mapOf("SPEAKER_02" to "Борис"))
    }

    @Test
    fun `the model is told about the library with its descriptions, the context the user built`() {
        libraryTag("budget", "Budget talks")
        librarySpeaker("Анна", "Head of finance")

        regenerateWith(Analysis("A new summary."))

        val input = asked.single()
        assertThat(input.tags.map { it.name to it.description }).containsExactly("budget" to "Budget talks")
        assertThat(input.speakers.map { it.name to it.description }).containsExactly("Анна" to "Head of finance")
    }

    @Test
    fun `what the user settles while it runs is kept`() {
        val budget = libraryTag("budget")
        libraryTag("work")
        suggestionService.record(submission, Analysis("First.", tags = listOf("budget", "work")))
        gate = CountDownLatch(1)
        answer = Analysis("A new summary.", tags = listOf("budget", "work"))

        assertThat(regenerate().status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(settle("tags/budget/accept")).isEqualTo(HttpStatus.OK) // while Gemini is being asked
        gate!!.countDown()
        waitForState("used")

        assertThat(attachedTags()).containsExactly("budget")
        assertThat(pendingTags()).containsExactly("work")
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM transcript_suggestions WHERE transcript_id = ? AND tag_id = ?", String::class.java, transcript, budget))
            .isEqualTo("accepted")
    }

    // ── Once ───────────────────────────────────────────────────────────────────

    @Test
    fun `a second click while it runs and after it is done is refused, and only one call is made`() {
        gate = CountDownLatch(1)

        assertThat(regenerate().status).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(regenState()).isEqualTo("running")
        assertThat(regenerate().status).isEqualTo(HttpStatus.CONFLICT) // while it runs

        gate!!.countDown()
        waitForState("used")

        assertThat(regenerate().status).isEqualTo(HttpStatus.CONFLICT) // after it
        assertThat(asked).hasSize(1)
    }

    @Test
    fun `a failed run gives the regeneration back and keeps the old summary`() {
        failWith = RuntimeException("Gemini error (503)")

        assertThat(regenerate().status).isEqualTo(HttpStatus.ACCEPTED)
        waitForState("available")

        assertThat(detail()["summary"].asText()).isEqualTo("The old summary.")
        assertThat(asked).hasSize(3) // tried three times, as the first analysis does

        failWith = null
        assertThat(regenerate().status).isEqualTo(HttpStatus.ACCEPTED)
        waitForState("used")
        assertThat(detail()["summary"].asText()).isEqualTo("A new summary.")
    }

    @Test
    fun `a run cut short by a restart is given back`() {
        jdbcTemplate.update("UPDATE submissions SET summary_regeneration = 'running' WHERE id = ?", submission)

        service.releaseInterrupted()

        assertThat(regenState()).isEqualTo("available")
    }

    // ── What the user has settled about the speakers goes in with the transcript ──

    private fun assign(body: String) = call(HttpMethod.PUT, "/transcriptions/$submission/speakers", body = body).status

    @Test
    fun `a transcription nobody has named is sent as it is, with the diarizer's labels`() {
        regenerateWith(Analysis("A new summary."))

        val input = asked.single()
        assertThat(input.text).contains("[SPEAKER_00] Привет, меня зовут Анна").contains("[SPEAKER_01]").contains("[SPEAKER_02]")
        assertThat(input.namesConfirmed).isFalse()
    }

    @Test
    fun `a voice the user named is sent under that name, and the voices nobody named keep their labels`() {
        librarySpeaker("Анна")
        suggestionService.record(submission, Analysis("First.", speakers = listOf(SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна"))))
        settle("speakers/SPEAKER_00/accept")

        regenerateWith(Analysis("A new summary."))

        val input = asked.single()
        assertThat(input.text).contains("[Анна] Привет, меня зовут Анна")
        assertThat(input.text).doesNotContain("SPEAKER_00")
        assertThat(input.text).contains("[SPEAKER_01] Спасибо, Анна").contains("[SPEAKER_02] Я Борис") // proposals still say which voice
        assertThat(input.namesConfirmed).isTrue()
    }

    @Test
    fun `a speaker set on a single phrase is sent under that name, and wins over the voice's`() {
        librarySpeaker("Анна")
        librarySpeaker("Борис")
        suggestionService.record(submission, Analysis("First.", speakers = listOf(SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна"))))
        settle("speakers/SPEAKER_00/accept")
        // Phrase 2 ("Я Борис...") is by the unnamed voice 2, and the user marked it by hand; phrase 0 by voice 0 is Анна.
        assertThat(assign("""{"scope": "segment", "segment_indexes": [2], "name": "Борис"}""")).isEqualTo(HttpStatus.OK)

        regenerateWith(Analysis("A new summary."))

        val input = asked.single()
        assertThat(input.text).contains("[Борис] Я Борис")
        assertThat(input.text).contains("[Анна] Привет").contains("[SPEAKER_01] Спасибо")
    }

    @Test
    fun `a proposal for a voice that is already named is still left alone`() {
        librarySpeaker("Анна")
        suggestionService.record(submission, Analysis("First.", speakers = listOf(SpeakerGuess("SPEAKER_00", "Анна", "меня зовут Анна"))))
        settle("speakers/SPEAKER_00/accept")

        regenerateWith(Analysis("A new summary.", speakers = listOf(
            SpeakerGuess("SPEAKER_00", "Другой", "меня зовут Анна"),
            SpeakerGuess("SPEAKER_02", "Борис", "Я Борис"),
        )))

        assertThat(namedVoices()).isEqualTo(mapOf("SPEAKER_00" to "Анна"))
        assertThat(pendingSpeakers()).isEqualTo(mapOf("SPEAKER_02" to "Борис"))
    }

    // ── The plan decides how many times ────────────────────────────────────────

    private fun userOnPlan(plan: String): UUID = newUser().also { jdbcTemplate.update("UPDATE users SET plan = ? WHERE id = ?", plan, it) }

    /** A plan of its own with [limit] regenerations per transcription (null: the key is left out, so no limit). */
    private fun planWithLimit(name: String, limit: Int?): String {
        val constraints = if (limit == null) "{}" else """{"regenerations_per_transcription": $limit}"""
        jdbcTemplate.update("INSERT INTO plans(name, display_name, constraints) VALUES (?, ?, ?::jsonb) ON CONFLICT (name) DO UPDATE SET constraints = EXCLUDED.constraints", name, name, constraints)
        return name
    }

    private fun regenState(user: UUID, tx: UUID) = call(HttpMethod.GET, "/transcriptions/$tx", user).body!!["summary_regeneration"]

    private fun regenerateAndWait(user: UUID, tx: UUID) {
        assertThat(regenerate(tx, user).status).isEqualTo(HttpStatus.ACCEPTED)
        val deadline = System.currentTimeMillis() + 15_000
        while (regenState(user, tx)["state"].asText() == "running") {
            check(System.currentTimeMillis() < deadline) { "still running" }
            Thread.sleep(50)
        }
    }

    @Test
    fun `an ultra user can regenerate the same transcription again and again, and it says when it was last done`() {
        val ultra = userOnPlan("unlimited")
        val (tx, _) = addTranscription(ultra)
        assertThat(regenState(ultra, tx).has("max")).isFalse()

        for (time in 1..3) {
            answer = Analysis("Summary number $time.")
            regenerateAndWait(ultra, tx)

            val state = regenState(ultra, tx)
            assertThat(state["state"].asText()).isEqualTo("available") // and again
            assertThat(state["used"].asInt()).isEqualTo(time)
            assertThat(state.has("max")).isFalse()
            assertThat(state["at"].asText()).isNotBlank()
            assertThat(call(HttpMethod.GET, "/transcriptions/$tx", ultra).body!!["summary"].asText()).isEqualTo("Summary number $time.")
        }
    }

    @Test
    fun `even an ultra user cannot start a second run of the same transcription while one is running`() {
        val ultra = userOnPlan("unlimited")
        val (tx, _) = addTranscription(ultra)
        gate = CountDownLatch(1)

        assertThat(regenerate(tx, ultra).status).isEqualTo(HttpStatus.ACCEPTED)
        val second = regenerate(tx, ultra)
        assertThat(second.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.body!!["message"].asText()).contains("being regenerated now")

        gate!!.countDown()
        val deadline = System.currentTimeMillis() + 15_000
        while (regenState(ultra, tx)["state"].asText() == "running") { // let the first one end
            check(System.currentTimeMillis() < deadline) { "still running" }
            Thread.sleep(50)
        }
        assertThat(asked).hasSize(1) // the refused click made no call
        regenerateAndWait(ultra, tx) // and then it can be done again
        assertThat(asked).hasSize(2)
    }

    @Test
    fun `a free user gets one, then is told that the plan's limit is used up`() {
        val free = userOnPlan("free")
        val (tx, _) = addTranscription(free)

        regenerateAndWait(free, tx)
        val state = regenState(free, tx)
        assertThat(state["state"].asText()).isEqualTo("used")
        assertThat(state["used"].asInt()).isEqualTo(1)
        assertThat(state["max"].asInt()).isEqualTo(1)

        val second = regenerate(tx, free)
        assertThat(second.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(second.body!!["message"].asText()).contains("allows regenerating the summary of a recording 1 time").contains("used up")
        assertThat(asked).hasSize(1)
    }

    @Test
    fun `the limit is per transcription, so another one of the same free user can still be regenerated`() {
        val free = userOnPlan("free")
        val (first, _) = addTranscription(free)
        val (second, _) = addTranscription(free)

        regenerateAndWait(free, first)

        assertThat(regenState(free, second)["state"].asText()).isEqualTo("available")
        regenerateAndWait(free, second)
        assertThat(regenState(free, second)["state"].asText()).isEqualTo("used")
    }

    @Test
    fun `an anonymous guest gets one too`() {
        val guest = userOnPlan("anonymous")
        val (tx, _) = addTranscription(guest)

        regenerateAndWait(guest, tx)

        assertThat(regenState(guest, tx)["state"].asText()).isEqualTo("used")
        assertThat(regenerate(tx, guest).status).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `a plan that allows two lets two through and refuses the third`() {
        val two = userOnPlan(planWithLimit("regen_two", 2))
        val (tx, _) = addTranscription(two)

        regenerateAndWait(two, tx)
        assertThat(regenState(two, tx)["state"].asText()).isEqualTo("available")
        regenerateAndWait(two, tx)

        assertThat(regenState(two, tx)["state"].asText()).isEqualTo("used")
        val third = regenerate(tx, two)
        assertThat(third.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(third.body!!["message"].asText()).contains("2 times")
        assertThat(asked).hasSize(2)
    }

    @Test
    fun `a plan with none has no button and refuses the call`() {
        val none = userOnPlan(planWithLimit("regen_none", 0))
        val (tx, _) = addTranscription(none)

        assertThat(regenState(none, tx)["state"].asText()).isEqualTo("unavailable")
        val reply = regenerate(tx, none)
        assertThat(reply.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(reply.body!!["message"].asText()).contains("does not include")
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a failed run is not counted against the limit`() {
        val free = userOnPlan("free")
        val (tx, _) = addTranscription(free)
        failWith = RuntimeException("Gemini error (503)")

        regenerateAndWait(free, tx)

        assertThat(regenState(free, tx)["used"].asInt()).isZero()
        assertThat(regenState(free, tx)["state"].asText()).isEqualTo("available")
        failWith = null
        regenerateAndWait(free, tx)
        assertThat(regenState(free, tx)["state"].asText()).isEqualTo("used")
    }

    @Test
    fun `switching the plan changes what is allowed at once, on the same transcription`() {
        val user = userOnPlan("free")
        val (tx, _) = addTranscription(user)
        regenerateAndWait(user, tx)
        assertThat(regenState(user, tx)["state"].asText()).isEqualTo("used")

        jdbcTemplate.update("UPDATE users SET plan = 'unlimited' WHERE id = ?", user)

        assertThat(regenState(user, tx)["state"].asText()).isEqualTo("available")
        regenerateAndWait(user, tx)
        assertThat(regenState(user, tx)["used"].asInt()).isEqualTo(2)
    }

    // ── Who and when ───────────────────────────────────────────────────────────

    @Test
    fun `someone else's transcription is not found, and a missing token is refused`() {
        assertThat(regenerate(user = newUser()).status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(regenerate(user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(call(HttpMethod.POST, "/transcriptions/not-an-id/regenerate-summary").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a transcription that is not done yet cannot be regenerated`() {
        val (pending, _) = addTranscription(userId, status = "processing")

        assertThat(regenerate(pending).status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `without a summary service the button is unavailable and the call is refused`() {
        available = false

        assertThat(regenState()).isEqualTo("unavailable")
        assertThat(regenerate().status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(asked).isEmpty()
    }
}
