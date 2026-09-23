package com.dictara.gateway

import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.service.OrchestratorService
import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/** What each entry of `GET /transcriptions` says about length, speakers and progress. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionListFieldsIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    /** Progress lives in the orchestrator's memory, so the tests tell it what it knows. */
    @MockBean lateinit var orchestrator: OrchestratorService

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private var minute = 0L

    @BeforeEach
    fun createUser() {
        userId = UUID.randomUUID()
        minute = 0
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
    }

    private fun addSubmission(
        name: String,
        status: String = "done",
        durationS: Double? = null,
        segments: String? = null,
        transcript: Boolean = segments != null,
    ): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val createdAt = java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z").plusSeconds(60 * minute++))
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?, ?)",
            audioId, userId, name, "gs://test/$name", "hash-$audioId", durationS,
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', ?, ?)",
            id, userId, audioId, status, createdAt,
        )
        if (transcript) {
            insertTranscript(UUID.randomUUID(), id, segments)
        }
        return id
    }

    private fun items(path: String = "/transcriptions?page=0&size=50"): Map<String, JsonNode> {
        val headers = HttpHeaders().apply { setBearerAuth(jwt.issue(userId)) }
        val response = rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = json.readTree(response.body)
        val list = if (body.isArray) body else body["items"]
        return list.associateBy { it["file_name"].asText() }
    }

    private fun segments(vararg speakers: String?) = speakers.mapIndexed { i, s ->
        val label = if (s == null) "null" else "\"$s\""
        """{"start": $i, "end": ${i + 1}, "text": "line $i", "speaker": $label}"""
    }.joinToString(prefix = "[", postfix = "]")

    // ── Duration ───────────────────────────────────────────────────────────────

    @Test
    fun `the duration of the audio is reported once it is known`() {
        addSubmission("known.mp3", durationS = 862.0)
        addSubmission("unknown.mp3", status = "processing")

        val found = items()

        assertThat(found["known.mp3"]!!["audio_duration_s"].asDouble()).isEqualTo(862.0)
        assertThat(found["unknown.mp3"]!!.has("audio_duration_s")).isFalse()
    }

    // ── Speakers ───────────────────────────────────────────────────────────────

    @Test
    fun `the speakers are the distinct diarizer labels of the transcript`() {
        addSubmission("three.mp3", segments = segments("SPEAKER_00", "SPEAKER_01", "SPEAKER_02", "SPEAKER_00", "SPEAKER_01"))
        addSubmission("two.mp3", segments = segments("SPEAKER_00", "SPEAKER_01", "SPEAKER_01"))
        addSubmission("one.mp3", segments = segments("SPEAKER_00", "SPEAKER_00"))

        val found = items()

        assertThat(found["three.mp3"]!!["speaker_count"].asInt()).isEqualTo(3)
        assertThat(found["two.mp3"]!!["speaker_count"].asInt()).isEqualTo(2)
        assertThat(found["one.mp3"]!!["speaker_count"].asInt()).isEqualTo(1)
    }

    @Test
    fun `without diarization there are no speakers`() {
        addSubmission("plain.mp3", segments = segments(null, null, null))

        assertThat(items()["plain.mp3"]!!["speaker_count"].asInt()).isZero()
    }

    @Test
    fun `a transcript with no segments at all has zero speakers and does not break the list`() {
        addSubmission("empty-array.mp3", segments = "[]")
        addSubmission("json-null.mp3", segments = "null") // a JSON null, not an SQL null
        addSubmission("sql-null.mp3", segments = null, transcript = true)
        addSubmission("scalar.mp3", segments = "\"oops\"")

        val found = items()

        for (name in listOf("empty-array.mp3", "json-null.mp3", "sql-null.mp3", "scalar.mp3")) {
            assertThat(found[name]!!["speaker_count"].asInt()).describedAs(name).isZero()
        }
    }

    @Test
    fun `no transcript means no speaker count`() {
        addSubmission("queued.mp3", status = "pending")

        assertThat(items()["queued.mp3"]!!.has("speaker_count")).isFalse()
    }

    @Test
    fun `a speaker label that is missing from some segments is not counted as a speaker`() {
        addSubmission("mixed.mp3", segments = """[{"start":0,"end":1,"text":"a","speaker":"SPEAKER_00"},{"start":1,"end":2,"text":"b"}]""")

        assertThat(items()["mixed.mp3"]!!["speaker_count"].asInt()).isEqualTo(1)
    }

    /** Names a library speaker, for the assignment tests below. */
    private fun speaker(name: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, ?)", id, userId, name)
        return id
    }

    private fun transcriptIdOf(submissionId: UUID): UUID =
        jdbcTemplate.queryForObject("SELECT id FROM transcripts WHERE submission_id = ?", UUID::class.java, submissionId)!!

    @Test
    fun `two voices confirmed as the same real speaker count once, not twice`() {
        val id = addSubmission("three.mp3", segments = segments("SPEAKER_00", "SPEAKER_01", "SPEAKER_02"))
        val transcriptId = transcriptIdOf(id)
        val maria = speaker("Maria Chen")
        // SPEAKER_00 and SPEAKER_01 both turn out to be Maria: three voices, but two real people.
        jdbcTemplate.update(
            "INSERT INTO transcript_voices(transcript_id, voice, speaker_id) VALUES (?, 'SPEAKER_00', ?), (?, 'SPEAKER_01', ?)",
            transcriptId, maria, transcriptId, maria,
        )

        assertThat(items()["three.mp3"]!!["speaker_count"].asInt()).isEqualTo(2)
    }

    @Test
    fun `a per-phrase mark to a new speaker is counted in addition to the voice's own`() {
        val id = addSubmission("two.mp3", segments = segments("SPEAKER_00", "SPEAKER_00"))
        val transcriptId = transcriptIdOf(id)
        val james = speaker("James Wright")
        // One of SPEAKER_00's two phrases is marked James by hand; the voice itself is still unconfirmed.
        jdbcTemplate.update(
            "UPDATE transcript_segments SET speaker_id = ? WHERE transcript_id = ? AND idx = 0",
            james, transcriptId,
        )

        assertThat(items()["two.mp3"]!!["speaker_count"].asInt()).isEqualTo(2) // "Speaker 1" (phrase 1) and James
    }

    // ── Progress ───────────────────────────────────────────────────────────────

    private fun progress(id: UUID, live: ProgressInfo?) {
        given(orchestrator.getLiveProgress(id)).willReturn(live)
    }

    @Test
    fun `while transcribing the percent of the audio done is reported`() {
        val id = addSubmission("busy.mp3", status = "processing")
        progress(id, ProgressInfo("transcribing", processedS = 25.0, totalS = 100.0))

        val item = items()["busy.mp3"]!!

        assertThat(item["processing_phase"].asText()).isEqualTo("transcribing")
        assertThat(item["processing_pct"].asInt()).isEqualTo(25)
    }

    @Test
    fun `while diarizing only the phase is reported`() {
        val id = addSubmission("busy.mp3", status = "processing")
        progress(id, ProgressInfo("diarizing", processedS = 100.0, totalS = 100.0, diarizeProgress = 0.4))

        val item = items()["busy.mp3"]!!

        assertThat(item["processing_phase"].asText()).isEqualTo("diarizing")
        assertThat(item.has("processing_pct")).isFalse()
    }

    @Test
    fun `while summarizing only the phase is reported`() {
        addSubmission("busy.mp3", status = "summarizing", segments = segments("SPEAKER_00"))

        val item = items()["busy.mp3"]!!

        assertThat(item["status"].asText()).isEqualTo("summarizing") // the status stays as the gateway has it
        assertThat(item["processing_phase"].asText()).isEqualTo("summarizing")
        assertThat(item.has("processing_pct")).isFalse()
    }

    @Test
    fun `a processing job the orchestrator knows nothing about has no phase and no percent`() {
        addSubmission("lost.mp3", status = "processing") // after a restart, before the first poll

        val item = items()["lost.mp3"]!!

        assertThat(item["status"].asText()).isEqualTo("processing")
        assertThat(item.has("processing_phase")).isFalse()
        assertThat(item.has("processing_pct")).isFalse()
    }

    @Test
    fun `transcribing with unknown totals has a phase but no percent`() {
        val id = addSubmission("busy.mp3", status = "processing")
        progress(id, ProgressInfo("transcribing"))

        val item = items()["busy.mp3"]!!

        assertThat(item["processing_phase"].asText()).isEqualTo("transcribing")
        assertThat(item.has("processing_pct")).isFalse()
    }

    @Test
    fun `progress is only reported for jobs that are being processed`() {
        val pending = addSubmission("waiting.mp3", status = "pending")
        val done = addSubmission("finished.mp3", status = "done", durationS = 10.0, segments = segments("SPEAKER_00"))
        val failed = addSubmission("broken.mp3", status = "failed")
        for (id in listOf(pending, done, failed)) progress(id, ProgressInfo("transcribing", processedS = 50.0, totalS = 100.0))

        val found = items()

        for (name in listOf("waiting.mp3", "finished.mp3", "broken.mp3")) {
            assertThat(found[name]!!.has("processing_pct")).describedAs(name).isFalse()
            assertThat(found[name]!!.has("processing_phase")).describedAs(name).isFalse()
        }
    }

    // ── Both shapes ────────────────────────────────────────────────────────────

    @Test
    fun `the plain array carries the same new fields as a page`() {
        addSubmission("call.mp3", durationS = 90.0, segments = segments("SPEAKER_00", "SPEAKER_01"))

        val paged = items("/transcriptions?page=0&size=10")["call.mp3"]!!
        val plain = items("/transcriptions")["call.mp3"]!!

        assertThat(plain).isEqualTo(paged)
        assertThat(plain["audio_duration_s"].asDouble()).isEqualTo(90.0)
        assertThat(plain["speaker_count"].asInt()).isEqualTo(2)
    }
}
