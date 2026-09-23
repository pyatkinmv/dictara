package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** `GET /transcriptions` narrowed by search, status and tags, and put in order. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionListFilterIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private var minute = 0L

    @BeforeEach
    fun createUser() {
        userId = UUID.randomUUID()
        minute = 0
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
    }

    /** Adds a transcription created a minute after the previous one, so a later call is newer. */
    private fun add(
        file: String,
        title: String? = null,
        status: String = "done",
        durationS: Double? = null,
        tags: List<String> = emptyList(),
        speakers: List<String> = emptyList(),
        owner: UUID = userId,
        withTranscript: Boolean = title != null || tags.isNotEmpty() || speakers.isNotEmpty(),
    ): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val createdAt = java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z").plusSeconds(60 * minute++))
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?, ?)",
            audioId, owner, file, "gs://test/$file", "hash-$audioId", durationS,
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', ?, ?)",
            id, owner, audioId, status, createdAt,
        )
        if (withTranscript) {
            val transcriptId = UUID.randomUUID()
            jdbcTemplate.update("INSERT INTO transcripts(id, submission_id, title) VALUES (?, ?, ?)", transcriptId, id, title)
            for (tag in tags) {
                val tagId = jdbcTemplate.query("SELECT id FROM tags WHERE user_id = ? AND name = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, owner, tag)
                    .firstOrNull() ?: UUID.randomUUID().also {
                    jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, ?)", it, owner, tag)
                }
                jdbcTemplate.update("INSERT INTO transcript_tags(transcript_id, tag_id) VALUES (?, ?)", transcriptId, tagId)
            }
            // One phrase per named speaker, marked as theirs directly (the simplest way to make a voice
            // resolve to a real speaker for the filter's purposes — see SubmissionListQuery).
            speakers.forEachIndexed { idx, name ->
                val speakerId = jdbcTemplate.query("SELECT id FROM speakers WHERE user_id = ? AND name = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, owner, name)
                    .firstOrNull() ?: UUID.randomUUID().also {
                    jdbcTemplate.update("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, ?)", it, owner, name)
                }
                jdbcTemplate.update(
                    "INSERT INTO transcript_segments(transcript_id, idx, start_s, end_s, text, voice, speaker_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    transcriptId, idx, idx.toDouble(), idx + 1.0, "line $idx", "SPEAKER_%02d".format(idx), speakerId,
                )
            }
        }
        return id
    }

    private fun request(path: String, params: List<Pair<String, String>>, user: UUID? = userId): ResponseEntity<String> {
        val uri = UriComponentsBuilder.fromPath(path).apply { params.forEach { (k, v) -> queryParam(k, v) } }
            .build().encode().toUri()
        val headers = HttpHeaders().apply { if (user != null) setBearerAuth(jwt.issue(user)) }
        return rest.exchange(uri, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    /** The titles shown in the list, in list order. */
    private fun shown(vararg params: Pair<String, String>): List<String> {
        val response = request("/transcriptions", params.toList())
        assertThat(response.statusCode).describedAs(params.toList().toString()).isEqualTo(HttpStatus.OK)
        return json.readTree(response.body).map { it["title"].asText() }
    }

    private fun page(page: Int, size: Int, vararg params: Pair<String, String>): JsonNode {
        val response = request("/transcriptions", params.toList() + listOf("page" to "$page", "size" to "$size"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return json.readTree(response.body)
    }

    private fun status(vararg params: Pair<String, String>) = request("/transcriptions", params.toList()).statusCode

    // ── Search ─────────────────────────────────────────────────────────────────

    @Test
    fun `search finds a part of the title, whatever the case`() {
        add("a.mp3", title = "Quarterly Review Meeting")
        add("b.mp3", title = "Daily Standup")
        add("c.mp3", title = "Product Ideas")

        assertThat(shown("q" to "review")).containsExactly("Quarterly Review Meeting")
        assertThat(shown("q" to "STAND")).containsExactly("Daily Standup")
        assertThat(shown("q" to "i")).containsExactly("Product Ideas", "Daily Standup", "Quarterly Review Meeting")
    }

    @Test
    fun `search knows other alphabets`() {
        add("a.mp3", title = "Разговор с клиентом")
        add("b.mp3", title = "Weekly call")

        assertThat(shown("q" to "РАЗГОВОР")).containsExactly("Разговор с клиентом")
        assertThat(shown("q" to "клиент")).containsExactly("Разговор с клиентом")
        assertThat(shown("q" to "école")).isEmpty()
    }

    @Test
    fun `without a title of its own a transcription is searched under its file name`() {
        add("product_sync_2025.mp4") // no transcript at all
        add("notes.mp3", title = null, withTranscript = true) // a transcript without a title

        assertThat(shown("q" to "product_sync")).containsExactly("product_sync_2025.mp4")
        assertThat(shown("q" to "NOTES")).containsExactly("notes.mp3")
    }

    @Test
    fun `a transcription with a title is found by that title, not by the file it came from`() {
        add("recording_0042.mp3", title = "Board meeting")

        assertThat(shown("q" to "board")).containsExactly("Board meeting")
        assertThat(shown("q" to "recording_0042")).isEmpty() // the list does not show that name
    }

    @Test
    fun `percent and underscore in the search are ordinary characters`() {
        add("a.mp3", title = "Growth 100% target")
        add("b.mp3", title = "Growth 1000 target")
        add("c.mp3", title = "snake_case notes")
        add("d.mp3", title = "snakeXcase notes")

        assertThat(shown("q" to "100%")).containsExactly("Growth 100% target")
        assertThat(shown("q" to "snake_case")).containsExactly("snake_case notes")
        assertThat(shown("q" to "%")).containsExactly("Growth 100% target")
        assertThat(shown("q" to "_")).containsExactly("snake_case notes")
        assertThat(shown("q" to "\\")).isEmpty()
    }

    @Test
    fun `a blank search returns everything and the text is trimmed`() {
        add("a.mp3", title = "One")
        add("b.mp3", title = "Two")

        assertThat(shown("q" to "")).hasSize(2)
        assertThat(shown("q" to "   ")).hasSize(2)
        assertThat(shown("q" to "  one  ")).containsExactly("One")
    }

    @Test
    fun `quotes and SQL in the search are just text`() {
        add("a.mp3", title = "It's a test")

        assertThat(shown("q" to "'")).containsExactly("It's a test")
        assertThat(shown("q" to "x'; DROP TABLE submissions; --")).isEmpty()
        assertThat(shown()).containsExactly("It's a test") // the table is still there
    }

    // ── Status ─────────────────────────────────────────────────────────────────

    private fun oneOfEach() {
        add("d.mp3", title = "done one", status = "done")
        add("p.mp3", title = "processing one", status = "processing")
        add("s.mp3", title = "summarizing one", status = "summarizing")
        add("q.mp3", title = "pending one", status = "pending")
        add("f.mp3", title = "failed one", status = "failed")
    }

    @Test
    fun `a status filter keeps only that status`() {
        oneOfEach()

        assertThat(shown("status" to "done")).containsExactly("done one")
        assertThat(shown("status" to "pending")).containsExactly("pending one")
        assertThat(shown("status" to "failed")).containsExactly("failed one")
    }

    @Test
    fun `processing covers summarizing too`() {
        oneOfEach()

        assertThat(shown("status" to "processing")).containsExactly("summarizing one", "processing one")
    }

    @Test
    fun `several statuses mean any of them`() {
        oneOfEach()

        assertThat(shown("status" to "done", "status" to "failed")).containsExactly("failed one", "done one")
        assertThat(shown("status" to "pending", "status" to "processing"))
            .containsExactly("pending one", "summarizing one", "processing one")
    }

    @Test
    fun `an unknown status is refused`() {
        oneOfEach()

        assertThat(status("status" to "finished")).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(status("status" to "done", "status" to "nope")).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(status("status" to "summarizing")).isEqualTo(HttpStatus.BAD_REQUEST) // only the client's names
        assertThat(status("status" to "DONE")).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    @Test
    fun `a tag filter keeps the transcriptions that carry it`() {
        add("a.mp3", title = "Standup", tags = listOf("meeting", "standup"))
        add("b.mp3", title = "Interview", tags = listOf("interview"))
        add("c.mp3", title = "Untagged", tags = emptyList(), withTranscript = true)
        add("d.mp3", title = "No transcript yet", status = "pending")

        assertThat(shown("tag" to "meeting")).containsExactly("Standup")
        assertThat(shown("tag" to "interview")).containsExactly("Interview")
    }

    @Test
    fun `several tags mean any of them`() {
        add("a.mp3", title = "Standup", tags = listOf("meeting", "standup"))
        add("b.mp3", title = "Interview", tags = listOf("interview"))
        add("c.mp3", title = "Ideas", tags = listOf("ideas"))

        assertThat(shown("tag" to "standup", "tag" to "ideas")).containsExactly("Ideas", "Standup")
        assertThat(shown("tag" to "meeting", "tag" to "standup")).containsExactly("Standup") // once, not twice
    }

    @Test
    fun `a tag that nobody carries, or that does not exist, gives an empty list`() {
        add("a.mp3", title = "Standup", tags = listOf("meeting"))

        assertThat(shown("tag" to "ideas")).isEmpty()
        assertThat(shown("tag" to "meeting", "tag" to "ideas")).containsExactly("Standup")
    }

    @Test
    fun `tag names are matched exactly, and a comma is part of a name`() {
        add("a.mp3", title = "Lower", tags = listOf("meeting"))
        add("b.mp3", title = "Comma", tags = listOf("q3,planning"))
        add("c.mp3", title = "Other", tags = listOf("q3"))

        assertThat(shown("tag" to "Meeting")).isEmpty()
        assertThat(shown("tag" to "meet")).isEmpty()
        assertThat(shown("tag" to "q3,planning")).containsExactly("Comma")
        assertThat(shown("tag" to "q3")).containsExactly("Other")
    }

    @Test
    fun `another user's tag of the same name does not match`() {
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        add("mine.mp3", title = "Mine", tags = listOf("meeting"))
        add("theirs.mp3", title = "Theirs", tags = listOf("meeting"), owner = other)

        assertThat(shown("tag" to "meeting")).containsExactly("Mine")
    }

    @Test
    fun `an empty tag parameter is no filter`() {
        add("a.mp3", title = "One", tags = listOf("meeting"))
        add("b.mp3", title = "Two")

        assertThat(shown("tag" to "")).hasSize(2)
    }

    // ── Speakers ───────────────────────────────────────────────────────────────

    @Test
    fun `a speaker filter keeps the transcriptions they speak in`() {
        add("a.mp3", title = "Standup", speakers = listOf("Alexei Sorokin", "Maria Chen"))
        add("b.mp3", title = "Interview", speakers = listOf("James Wright"))
        add("c.mp3", title = "No one confirmed", speakers = emptyList(), withTranscript = true)
        add("d.mp3", title = "No transcript yet", status = "pending")

        assertThat(shown("speaker" to "Alexei Sorokin")).containsExactly("Standup")
        assertThat(shown("speaker" to "James Wright")).containsExactly("Interview")
    }

    @Test
    fun `several speakers mean any of them`() {
        add("a.mp3", title = "Standup", speakers = listOf("Alexei Sorokin", "Maria Chen"))
        add("b.mp3", title = "Interview", speakers = listOf("James Wright"))
        add("c.mp3", title = "Ideas", speakers = listOf("Dana Whitfield"))

        assertThat(shown("speaker" to "Maria Chen", "speaker" to "Dana Whitfield")).containsExactly("Ideas", "Standup")
        assertThat(shown("speaker" to "Alexei Sorokin", "speaker" to "Maria Chen")).containsExactly("Standup") // once, not twice
    }

    @Test
    fun `a speaker who says nothing here, or does not exist, gives an empty list`() {
        add("a.mp3", title = "Standup", speakers = listOf("Alexei Sorokin"))

        assertThat(shown("speaker" to "Dana Whitfield")).isEmpty()
        assertThat(shown("speaker" to "Alexei Sorokin", "speaker" to "Dana Whitfield")).containsExactly("Standup")
    }

    @Test
    fun `an unconfirmed voice never matches, only a real name does`() {
        // A diarized transcript with no per-phrase mark and no confirmed transcript_voices row: the voice
        // resolves to nobody, so it can never satisfy a speaker filter, however the placeholder is spelled.
        val id = add("a.mp3", title = "Unassigned", withTranscript = true)
        val transcriptId = jdbcTemplate.queryForObject("SELECT id FROM transcripts WHERE submission_id = ?", UUID::class.java, id)
        jdbcTemplate.update(
            "INSERT INTO transcript_segments(transcript_id, idx, start_s, end_s, text, voice) VALUES (?, 0, 0, 1, 'hi', 'SPEAKER_00')",
            transcriptId,
        )

        assertThat(shown("speaker" to "Speaker 1")).isEmpty()
    }

    @Test
    fun `another user's speaker of the same name does not match`() {
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        add("mine.mp3", title = "Mine", speakers = listOf("Alexei Sorokin"))
        add("theirs.mp3", title = "Theirs", speakers = listOf("Alexei Sorokin"), owner = other)

        assertThat(shown("speaker" to "Alexei Sorokin")).containsExactly("Mine")
    }

    @Test
    fun `an empty speaker parameter is no filter`() {
        add("a.mp3", title = "One", speakers = listOf("Alexei Sorokin"))
        add("b.mp3", title = "Two")

        assertThat(shown("speaker" to "")).hasSize(2)
    }

    @Test
    fun `speaker and tag filters combine`() {
        add("a.mp3", title = "Match", tags = listOf("meeting"), speakers = listOf("Alexei Sorokin"))
        add("b.mp3", title = "Wrong tag", tags = listOf("interview"), speakers = listOf("Alexei Sorokin"))
        add("c.mp3", title = "Wrong speaker", tags = listOf("meeting"), speakers = listOf("James Wright"))

        assertThat(shown("tag" to "meeting", "speaker" to "Alexei Sorokin")).containsExactly("Match")
    }

    // ── Sort ───────────────────────────────────────────────────────────────────

    @Test
    fun `newest is the default and oldest reverses it`() {
        add("a.mp3", title = "First")
        add("b.mp3", title = "Second")
        add("c.mp3", title = "Third")

        assertThat(shown()).containsExactly("Third", "Second", "First")
        assertThat(shown("sort" to "newest")).containsExactly("Third", "Second", "First")
        assertThat(shown("sort" to "oldest")).containsExactly("First", "Second", "Third")
    }

    @Test
    fun `longest puts the longest audio first and unknown lengths last`() {
        add("a.mp3", title = "Short", durationS = 60.0)
        add("b.mp3", title = "Unknown", status = "processing")
        add("c.mp3", title = "Long", durationS = 3600.0)
        add("d.mp3", title = "Also unknown", status = "pending")
        add("e.mp3", title = "Medium", durationS = 600.0)

        assertThat(shown("sort" to "longest")).containsExactly("Long", "Medium", "Short", "Also unknown", "Unknown")
    }

    @Test
    fun `equal lengths are ordered newest first`() {
        add("a.mp3", title = "Older", durationS = 100.0)
        add("b.mp3", title = "Newer", durationS = 100.0)

        assertThat(shown("sort" to "longest")).containsExactly("Newer", "Older")
    }

    @Test
    fun `name sorts by the shown title without regard to case`() {
        add("z.mp3", title = "banana")
        add("y.mp3", title = "Apple")
        add("x.mp3", title = "cherry")
        add("apple_pie.mp3") // no title: sorted under its file name
        add("Date.mp3", title = null, withTranscript = true)

        assertThat(shown("sort" to "name")).containsExactly("Apple", "apple_pie.mp3", "banana", "cherry", "Date.mp3")
    }

    @Test
    fun `equal names are ordered newest first`() {
        add("a.mp3", title = "Same")
        add("b.mp3", title = "same")

        assertThat(shown("sort" to "name")).containsExactly("same", "Same")
    }

    @Test
    fun `an unknown sort is refused`() {
        assertThat(status("sort" to "random")).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(status("sort" to "NEWEST")).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── Together, and with pages ───────────────────────────────────────────────

    @Test
    fun `search, status, tags and sort combine`() {
        add("a.mp3", title = "Team standup Monday", tags = listOf("standup"), durationS = 300.0)
        add("b.mp3", title = "Team standup Tuesday", tags = listOf("standup"), durationS = 900.0)
        add("c.mp3", title = "Team standup Wednesday", tags = listOf("standup"), status = "failed")
        add("d.mp3", title = "Team offsite", tags = listOf("meeting"), durationS = 5000.0)
        add("e.mp3", title = "Lunch", tags = listOf("standup"), durationS = 5000.0)

        val found = shown("q" to "team", "status" to "done", "tag" to "standup", "sort" to "longest")

        assertThat(found).containsExactly("Team standup Tuesday", "Team standup Monday")
    }

    @Test
    fun `pages are cut from the filtered and sorted list`() {
        (1..7).forEach { add("f$it.mp3", title = "Call $it", tags = listOf("call"), durationS = it * 10.0) }
        add("other.mp3", title = "Other", tags = listOf("misc"), durationS = 999.0)
        val filters = arrayOf("tag" to "call", "sort" to "longest")

        val first = page(0, 3, *filters)
        val second = page(1, 3, *filters)
        val third = page(2, 3, *filters)

        assertThat(first["items"].map { it["title"].asText() }).containsExactly("Call 7", "Call 6", "Call 5")
        assertThat(first["has_next"].asBoolean()).isTrue()
        assertThat(second["items"].map { it["title"].asText() }).containsExactly("Call 4", "Call 3", "Call 2")
        assertThat(second["has_next"].asBoolean()).isTrue()
        assertThat(third["items"].map { it["title"].asText() }).containsExactly("Call 1")
        assertThat(third["has_next"].asBoolean()).isFalse()
    }

    @Test
    fun `a filter with no match gives an empty page`() {
        add("a.mp3", title = "One")

        val body = page(0, 10, "q" to "nothing like this")

        assertThat(body["items"].size()).isZero()
        assertThat(body["has_next"].asBoolean()).isFalse()
    }

    @Test
    fun `the plain array is narrowed and ordered in the same way`() {
        add("a.mp3", title = "Alpha", tags = listOf("x"))
        add("b.mp3", title = "Beta", tags = listOf("x"))
        add("c.mp3", title = "Gamma")

        val plain = shown("tag" to "x", "sort" to "name")
        val paged = page(0, 10, "tag" to "x", "sort" to "name")["items"].map { it["title"].asText() }

        assertThat(plain).containsExactly("Alpha", "Beta")
        assertThat(paged).isEqualTo(plain)
    }

    @Test
    fun `a filtered entry looks the same as an unfiltered one`() {
        add("a.mp3", title = "Call", tags = listOf("meeting", "team"), durationS = 90.0)

        val unfiltered = json.readTree(request("/transcriptions", emptyList()).body)[0]
        val filtered = json.readTree(request("/transcriptions", listOf("tag" to "meeting", "q" to "call")).body)[0]

        assertThat(filtered).isEqualTo(unfiltered)
        assertThat(filtered["tags"].map { it.asText() }).containsExactly("meeting", "team") // all its tags, not just the filtered one
    }

    @Test
    fun `only the user's own transcriptions are ever considered`() {
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        add("mine.mp3", title = "Shared name")
        add("theirs.mp3", title = "Shared name", owner = other, durationS = 9999.0)

        assertThat(shown("q" to "shared")).containsExactly("Shared name")
        assertThat(shown("sort" to "longest")).hasSize(1)
    }

    @Test
    fun `filters need a token like the list itself`() {
        assertThat(request("/transcriptions", listOf("q" to "x"), user = null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── The tag library ────────────────────────────────────────────────────────

    @Test
    fun `GET tags lists the user's tags in the order they were made`() {
        val early = java.sql.Timestamp.from(Instant.parse("2026-01-01T09:00:00Z"))
        val late = java.sql.Timestamp.from(Instant.parse("2026-01-02T09:00:00Z"))
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name, created_at) VALUES (?, ?, 'zebra', ?)", UUID.randomUUID(), userId, early)
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name, created_at) VALUES (?, ?, 'alpha', ?)", UUID.randomUUID(), userId, late)
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name, created_at) VALUES (?, ?, 'beta', ?)", UUID.randomUUID(), userId, late)

        val body = json.readTree(request("/tags", emptyList()).body)

        assertThat(body["tags"].map { it["label"].asText() }).containsExactly("zebra", "alpha", "beta")
        assertThat(body["tags"].first().fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "label", "color", "transcription_count", "sample_titles") // no description: left out
        assertThat(UUID.fromString(body["tags"].first()["id"].asText())).isNotNull()
    }

    @Test
    fun `GET tags includes tags no transcription carries, and only the user's own`() {
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, 'unused')", UUID.randomUUID(), userId)
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, 'theirs')", UUID.randomUUID(), other)

        val labels = json.readTree(request("/tags", emptyList()).body)["tags"].map { it["label"].asText() }

        assertThat(labels).containsExactly("unused")
    }

    @Test
    fun `GET tags is empty for a user without tags and needs a token`() {
        assertThat(json.readTree(request("/tags", emptyList()).body)["tags"].size()).isZero()
        assertThat(request("/tags", emptyList(), user = null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
