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
import java.time.Instant
import java.util.UUID

/** `GET /transcriptions`: the plain array the old client relies on, and the same list in pages. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TranscriptionListPagingIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private val base = Instant.parse("2026-01-01T10:00:00Z")

    @BeforeEach
    fun createUser() {
        userId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
    }

    /** Adds a transcription for [owner]; [minutes] after the base time, so a larger number is newer. */
    private fun addSubmission(
        name: String,
        minutes: Long,
        owner: UUID = userId,
        status: String = "done",
        title: String? = null,
        tags: List<String> = emptyList(),
        id: UUID = UUID.randomUUID(),
    ): UUID {
        val audioId = UUID.randomUUID()
        val createdAt = java.sql.Timestamp.from(base.plusSeconds(minutes * 60))
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?)",
            audioId, owner, name, "gs://test/$name", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', ?, ?)",
            id, owner, audioId, status, createdAt,
        )
        if (title != null || tags.isNotEmpty()) {
            val transcriptId = UUID.randomUUID()
            jdbcTemplate.update("INSERT INTO transcripts(id, submission_id, title) VALUES (?, ?, ?)", transcriptId, id, title)
            for (tag in tags) {
                val tagId = jdbcTemplate.query("SELECT id FROM tags WHERE user_id = ? AND name = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, owner, tag)
                    .firstOrNull() ?: UUID.randomUUID().also {
                    jdbcTemplate.update("INSERT INTO tags(id, user_id, name) VALUES (?, ?, ?)", it, owner, tag)
                }
                jdbcTemplate.update("INSERT INTO transcript_tags(transcript_id, tag_id) VALUES (?, ?)", transcriptId, tagId)
            }
        }
        return id
    }

    private fun get(path: String, user: UUID? = userId): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (user != null) headers.setBearerAuth(jwt.issue(user))
        return rest.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    private fun ok(path: String): JsonNode {
        val response = get(path)
        assertThat(response.statusCode).describedAs("GET $path").isEqualTo(HttpStatus.OK)
        return json.readTree(response.body)
    }

    private fun names(items: JsonNode) = items.map { it["file_name"].asText() }

    /** Seven files a..g, g the newest. */
    private fun sevenFiles() = ('a'..'g').forEachIndexed { i, c -> addSubmission("$c.mp3", minutes = i.toLong()) }

    // ── The old shape ──────────────────────────────────────────────────────────

    @Test
    fun `without paging parameters the list is a plain array, newest first`() {
        sevenFiles()

        val body = ok("/transcriptions")

        assertThat(body.isArray).isTrue()
        assertThat(names(body)).containsExactly("g.mp3", "f.mp3", "e.mp3", "d.mp3", "c.mp3", "b.mp3", "a.mp3")
    }

    @Test
    fun `the plain array keeps its fields`() {
        addSubmission("call.mp3", 0, title = "Weekly call", tags = listOf("meeting", "team"))

        val item = ok("/transcriptions")[0]

        assertThat(item.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "job_id", "file_name", "title", "created_at", "status", "tags",
        )
        assertThat(item["title"].asText()).isEqualTo("Weekly call")
        assertThat(item["tags"].map { it.asText() }).containsExactly("meeting", "team")
        assertThat(item["status"].asText()).isEqualTo("done")
    }

    @Test
    fun `an empty list is an empty array`() {
        assertThat(ok("/transcriptions").isArray).isTrue()
        assertThat(ok("/transcriptions").size()).isZero()
    }

    // ── Pages ──────────────────────────────────────────────────────────────────

    @Test
    fun `pages are slices of the newest-first list and say whether more follow`() {
        sevenFiles()

        val first = ok("/transcriptions?page=0&size=3")
        val second = ok("/transcriptions?page=1&size=3")
        val third = ok("/transcriptions?page=2&size=3")

        assertThat(names(first["items"])).containsExactly("g.mp3", "f.mp3", "e.mp3")
        assertThat(first["has_next"].asBoolean()).isTrue()
        assertThat(names(second["items"])).containsExactly("d.mp3", "c.mp3", "b.mp3")
        assertThat(second["has_next"].asBoolean()).isTrue()
        assertThat(names(third["items"])).containsExactly("a.mp3")
        assertThat(third["has_next"].asBoolean()).isFalse()
    }

    @Test
    fun `the response repeats the page and size it answers`() {
        sevenFiles()

        val body = ok("/transcriptions?page=1&size=3")

        assertThat(body["page"].asInt()).isEqualTo(1)
        assertThat(body["size"].asInt()).isEqualTo(3)
        assertThat(body.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("items", "page", "size", "has_next")
    }

    @Test
    fun `all pages together are the whole list, once each`() {
        sevenFiles()

        val paged = (0..3).flatMap { names(ok("/transcriptions?page=$it&size=2")["items"]) }

        assertThat(paged).isEqualTo(names(ok("/transcriptions")))
    }

    @Test
    fun `a page has the same entries as the array`() {
        addSubmission("call.mp3", 0, title = "Weekly call", tags = listOf("meeting"))

        assertThat(ok("/transcriptions?page=0&size=10")["items"]).isEqualTo(ok("/transcriptions"))
    }

    @Test
    fun `exactly a full last page has no next page`() {
        sevenFiles()

        val last = ok("/transcriptions?page=1&size=4") // 4 + 3

        assertThat(last["items"].size()).isEqualTo(3)
        assertThat(last["has_next"].asBoolean()).isFalse()
        assertThat(ok("/transcriptions?page=0&size=7")["has_next"].asBoolean()).isFalse()
        assertThat(ok("/transcriptions?page=0&size=6")["has_next"].asBoolean()).isTrue()
    }

    @Test
    fun `a page past the end is empty`() {
        sevenFiles()

        val body = ok("/transcriptions?page=5&size=3")

        assertThat(body["items"].size()).isZero()
        assertThat(body["has_next"].asBoolean()).isFalse()
    }

    @Test
    fun `size alone means the first page, page alone uses the default size`() {
        (0 until 35).forEach { addSubmission("f$it.mp3", it.toLong()) }

        val bySize = ok("/transcriptions?size=5")
        assertThat(bySize["page"].asInt()).isZero()
        assertThat(bySize["items"].size()).isEqualTo(5)

        val byPage = ok("/transcriptions?page=0")
        assertThat(byPage["size"].asInt()).isEqualTo(30)
        assertThat(byPage["items"].size()).isEqualTo(30)
        assertThat(byPage["has_next"].asBoolean()).isTrue()
        assertThat(ok("/transcriptions?page=1")["items"].size()).isEqualTo(5)
    }

    @Test
    fun `the largest allowed size is served in one page`() {
        sevenFiles()

        assertThat(ok("/transcriptions?size=500")["items"].size()).isEqualTo(7)
    }

    @Test
    fun `entries created at the same moment are split between pages without loss or repeats`() {
        // Ties on the date are broken by id, so the order is total and stable.
        val ids = (0 until 5).map { addSubmission("same$it.mp3", minutes = 1) }

        val paged = (0..2).flatMap { ok("/transcriptions?page=$it&size=2")["items"].map { item -> item["job_id"].asText() } }

        assertThat(paged).containsExactlyInAnyOrderElementsOf(ids.map { it.toString() })
        assertThat(paged).doesNotHaveDuplicates()
        assertThat(paged).isEqualTo(ok("/transcriptions").map { it["job_id"].asText() })
    }

    @Test
    fun `a page carries titles, tags and statuses`() {
        addSubmission("old.mp3", 0, status = "failed")
        addSubmission("new.mp3", 1, status = "processing")
        addSubmission("mid.mp3", 2, title = "Named", tags = listOf("b", "a"))

        val items = ok("/transcriptions?page=0&size=10")["items"]

        val named = items.first { it["file_name"].asText() == "mid.mp3" }
        assertThat(named["title"].asText()).isEqualTo("Named")
        assertThat(named["tags"].map { it.asText() }).containsExactly("a", "b")
        assertThat(items.first { it["file_name"].asText() == "new.mp3" }["status"].asText()).isEqualTo("processing")
        assertThat(items.first { it["file_name"].asText() == "old.mp3" }["title"].asText()).isEqualTo("old.mp3")
    }

    @Test
    fun `a user only sees their own transcriptions, on every page`() {
        sevenFiles()
        val other = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", other)
        addSubmission("secret.mp3", 100, owner = other)

        val own = (0..3).flatMap { names(ok("/transcriptions?page=$it&size=2")["items"]) }
        assertThat(own).doesNotContain("secret.mp3").hasSize(7)

        val theirs = ok("/transcriptions?page=0&size=10").let { names(it["items"]) }
        assertThat(theirs).doesNotContain("secret.mp3")
        val otherView = json.readTree(get("/transcriptions?page=0&size=10", user = other).body)
        assertThat(names(otherView["items"])).containsExactly("secret.mp3")
    }

    // ── Bad requests ───────────────────────────────────────────────────────────

    @Test
    fun `invalid paging parameters are a bad request`() {
        for (query in listOf("size=0", "size=501", "size=-1", "page=-1&size=5", "size=abc", "page=x")) {
            assertThat(get("/transcriptions?$query").statusCode).describedAs(query).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `without a token the list is refused, paged or not`() {
        assertThat(get("/transcriptions", user = null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get("/transcriptions?page=0&size=5", user = null).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
