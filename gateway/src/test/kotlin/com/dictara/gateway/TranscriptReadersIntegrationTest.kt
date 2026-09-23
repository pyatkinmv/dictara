package com.dictara.gateway

import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * The phrases of a transcript are rows (`transcript_segments`). `GET /jobs/{id}` (segments and
 * `formatted_text`) is the old web client's and the Telegram bot's reader and must not change: raw diarizer
 * labels (`SPEAKER_00`), untouched by any speaker assignment. `GET /transcript` (the `.txt`) and the export
 * are read by people, not old clients, so they show who actually speaks — resolved the same way the
 * Speaker Picker's page does (see [SpeakerAssignmentIntegrationTest]): a per-phrase mark, else the voice's
 * confirmed speaker, else "Speaker N" — never the raw label.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptReadersIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var submissions: SubmissionService
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID

    @BeforeEach
    fun createUser() {
        userId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
    }

    private fun addTranscription(name: String, segments: String?): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?)",
            audioId, userId, name, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', true, 'off', 'web', 'done')",
            id, userId, audioId,
        )
        if (segments != null) insertTranscript(UUID.randomUUID(), id, segments)
        return id
    }

    private val diarized = """[
        {"start": 0, "end": 30.5, "text": "Good morning.", "speaker": "SPEAKER_00"},
        {"start": 3671.789, "end": 3700, "text": "Later, \"quoted\".", "speaker": "SPEAKER_01"},
        {"start": 3700, "end": 3701.25, "text": "No speaker here."}
    ]"""

    @Test
    fun `GET jobs keeps its shape - raw diarizer labels, times, formatted text`() {
        val id = addTranscription("meeting.m4a", diarized)

        val result = json.readTree(rest.getForObject("/jobs/$id", String::class.java))["result"]

        assertThat(result["segments"]).hasSize(3)
        assertThat(result["segments"][0].fieldNames().asSequence().toList()).containsExactly("start", "end", "text", "speaker")
        assertThat(result["segments"][0]["speaker"].asText()).isEqualTo("SPEAKER_00")
        assertThat(result["segments"][1]["start"].asDouble()).isEqualTo(3671.789)
        assertThat(result["segments"][2].has("speaker")).describedAs("no diarization: the field is left out").isFalse()
        assertThat(result["formatted_text"].asText()).isEqualTo(
            "[00:00:00.000 --> 00:00:30.500] [SPEAKER_00] Good morning.\n" +
                "[01:01:11.789 --> 01:01:40.000] [SPEAKER_01] Later, \"quoted\".\n" +
                "[01:01:40.000 --> 01:01:41.250] No speaker here.",
        )
    }

    @Test
    fun `GET jobs of a transcript without phrases has an empty list, of one not made yet no result at all`() {
        val empty = addTranscription("empty.mp3", "[]")
        val none = addTranscription("pending.mp3", null)

        val emptyResult = json.readTree(rest.getForObject("/jobs/$empty", String::class.java))["result"]
        assertThat(emptyResult["segments"]).isEmpty()
        assertThat(emptyResult["formatted_text"].asText()).isEmpty()
        assertThat(json.readTree(rest.getForObject("/jobs/$none", String::class.java)).has("result")).isFalse()
    }

    @Test
    fun `the txt download resolves unassigned voices to Speaker N, not the raw label, and a transcript without phrases is not found`() {
        val id = addTranscription("meeting.m4a", diarized)
        val empty = addTranscription("empty.mp3", "[]")

        val ok = rest.getForEntity("/transcript?jobId=$id", String::class.java)

        assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(ok.body).isEqualTo(
            "[00:00:00.000 --> 00:00:30.500] [Speaker 1] Good morning.\n" +
                "[01:01:11.789 --> 01:01:40.000] [Speaker 2] Later, \"quoted\".\n" +
                "[01:01:40.000 --> 01:01:41.250] No speaker here.", // no diarization: no bracket at all
        )
        assertThat(rest.getForEntity("/transcript?jobId=$empty", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an assigned voice and a per-phrase mark resolve to their real names in the txt download`() {
        val id = addTranscription("meeting.m4a", diarized)
        val speakerId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, 'Maria Chen')", speakerId, userId)
        val transcriptId = jdbcTemplate.queryForObject("SELECT id FROM transcripts WHERE submission_id = ?", UUID::class.java, id)
        jdbcTemplate.update(
            "INSERT INTO transcript_voices(transcript_id, voice, speaker_id) VALUES (?, 'SPEAKER_00', ?)",
            transcriptId, speakerId,
        )
        val otherId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, 'James Wright')", otherId, userId)
        jdbcTemplate.update(
            "UPDATE transcript_segments SET speaker_id = ? WHERE transcript_id = ? AND idx = 1", // overrides voice SPEAKER_01
            otherId, transcriptId,
        )

        val text = rest.getForEntity("/transcript?jobId=$id", String::class.java).body

        assertThat(text).isEqualTo(
            "[00:00:00.000 --> 00:00:30.500] [Maria Chen] Good morning.\n" + // SPEAKER_00's confirmed voice
                "[01:01:11.789 --> 01:01:40.000] [James Wright] Later, \"quoted\".\n" + // its own mark wins over the voice
                "[01:01:40.000 --> 01:01:41.250] No speaker here.",
        )
        // GET /jobs/{id} is the old client's reader: unaffected, still the raw diarizer label.
        val jobsResult = json.readTree(rest.getForObject("/jobs/$id", String::class.java))["result"]
        assertThat(jobsResult["formatted_text"].asText()).contains("[SPEAKER_00] Good morning.")
    }

    @Test
    fun `the export carries the same resolved text as the txt download`() {
        val id = addTranscription("meeting.m4a", diarized)
        addTranscription("empty.mp3", "[]")

        val exported = submissions.loadExportData(userId).associateBy { it.originalName }

        assertThat(exported["meeting.m4a"]!!.submissionId).isEqualTo(id)
        assertThat(exported["meeting.m4a"]!!.transcriptText).isEqualTo(
            rest.getForEntity("/transcript?jobId=$id", String::class.java).body,
        )
        assertThat(exported["empty.mp3"]!!.transcriptText).isEmpty()
    }

    @Test
    fun `phrases come back in their order however they were inserted`() {
        val segments = (0 until 200).joinToString(prefix = "[", postfix = "]") { """{"start": $it, "end": ${it + 1}, "text": "p$it", "speaker": "SPEAKER_00"}""" }
        val id = addTranscription("long.mp3", segments)
        // Rewrite the physical order of the rows: an update moves a row to the end of the table.
        jdbcTemplate.update("UPDATE transcript_segments SET text = text WHERE idx % 2 = 0")

        val texts = json.readTree(rest.getForObject("/jobs/$id", String::class.java))["result"]["segments"].map { it["text"].asText() }

        assertThat(texts).isEqualTo((0 until 200).map { "p$it" })
    }
}
