package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.util.TagColors
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
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID

/** The tag library (`/tags`) and the tags on a transcription (`/transcriptions/{id}/tags`). */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TagLibraryIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private val json = ObjectMapper()
    private lateinit var userId: UUID
    private var minute = 0L

    @BeforeEach
    fun createUser() {
        userId = newUser()
        minute = 0
    }

    private fun newUser() = UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", it) }

    private class Reply(val status: HttpStatus, val body: JsonNode?)

    private fun call(method: HttpMethod, path: String, body: String? = null, user: UUID? = userId): Reply {
        val headers = HttpHeaders().apply {
            user?.let { setBearerAuth(jwt.issue(it)) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(HttpStatus.valueOf(response.statusCode.value()), text?.let(json::readTree))
    }

    private fun get(path: String, user: UUID? = userId) = call(HttpMethod.GET, path, user = user)
    private fun post(path: String, body: String, user: UUID? = userId) = call(HttpMethod.POST, path, body, user)
    private fun put(path: String, body: String, user: UUID? = userId) = call(HttpMethod.PUT, path, body, user)
    private fun delete(path: String, user: UUID? = userId) = call(HttpMethod.DELETE, path, user = user)

    private fun library(user: UUID = userId) = get("/tags", user).body!!
    private fun labels(user: UUID = userId) = library(user)["tags"].map { it["label"].asText() }

    /** A finished transcription of [owner]; returns its submission id. */
    private fun addTranscription(name: String, owner: UUID = userId, title: String? = null, transcript: Boolean = true): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        val createdAt = java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z").plusSeconds(60 * minute++))
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?)",
            audioId, owner, name, "gs://test/$audioId", "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done', ?)",
            id, owner, audioId, createdAt,
        )
        if (transcript) {
            jdbcTemplate.update("INSERT INTO transcripts(id, submission_id, segments, title) VALUES (?, ?, '[]'::jsonb, ?)", UUID.randomUUID(), id, title)
        }
        return id
    }

    private fun createTag(label: String, description: String? = null, user: UUID = userId): UUID {
        val body = if (description == null) """{"label": "$label"}""" else """{"label": "$label", "description": "$description"}"""
        return UUID.fromString(post("/tags", body, user).body!!["id"].asText())
    }

    private fun tagsOf(submission: UUID): List<String> = get("/transcriptions/$submission").body!!["tags"].map { it["label"].asText() }

    // ── Creating ───────────────────────────────────────────────────────────────

    @Test
    fun `a new tag is created with its color and shows up in the library`() {
        val reply = post("/tags", """{"label": "meeting", "description": "Regular meetings"}""")

        assertThat(reply.status).isEqualTo(HttpStatus.CREATED)
        assertThat(reply.body!!["label"].asText()).isEqualTo("meeting")
        assertThat(reply.body["color"].asText()).isEqualTo(TagColors.of("meeting"))
        val entry = library()["tags"].single()
        assertThat(entry["id"].asText()).isEqualTo(reply.body["id"].asText())
        assertThat(entry["description"].asText()).isEqualTo("Regular meetings")
        assertThat(entry["transcription_count"].asInt()).isZero()
        assertThat(entry["sample_titles"]).isEmpty()
    }

    @Test
    fun `a name is trimmed and lower-cased, and any language is fine`() {
        assertThat(post("/tags", """{"label": "  Встреча  "}""").body!!["label"].asText()).isEqualTo("встреча")
        assertThat(post("/tags", """{"label": "Q3_plan-2"}""").body!!["label"].asText()).isEqualTo("q3_plan-2")
        assertThat(post("/tags", """{"label": "会议"}""").status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `names that are empty, have spaces or marks, or are too long are refused`() {
        for (bad in listOf("", "   ", "two words", "a!", "a/b", "x".repeat(33))) {
            assertThat(post("/tags", """{"label": "$bad"}""").status).describedAs("'$bad'").isEqualTo(HttpStatus.BAD_REQUEST)
        }
        assertThat(post("/tags", "{}").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/tags", """{"label": "${"x".repeat(32)}"}""").status).isEqualTo(HttpStatus.CREATED)
        assertThat(labels()).hasSize(1)
    }

    @Test
    fun `a name that is taken, in any case, is a 409 - but only for the same user`() {
        createTag("meeting")

        assertThat(post("/tags", """{"label": "meeting"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(post("/tags", """{"label": "MEETING"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(post("/tags", """{"label": "meeting"}""", user = newUser()).status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `a description is trimmed, and a very long one is refused`() {
        createTag("a", "  needs trimming  ")
        assertThat(library()["tags"].single()["description"].asText()).isEqualTo("needs trimming")

        assertThat(post("/tags", """{"label": "b", "description": "${"d".repeat(501)}"}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/tags", """{"label": "c", "description": "${"d".repeat(500)}"}""").status).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `the library needs a token`() {
        assertThat(get("/tags", user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(post("/tags", """{"label": "a"}""", user = null).status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── The library ────────────────────────────────────────────────────────────

    @Test
    fun `each tag says how many transcriptions carry it and the titles of the latest two`() {
        val old = addTranscription("old.mp3")
        val named = addTranscription("mid.mp3", title = "Named")
        val newest = addTranscription("new.mp3")
        val tag = createTag("meeting")
        for (s in listOf(old, named, newest)) post("/transcriptions/$s/tags", """{"tag_id": "$tag"}""")
        createTag("unused")

        val entries = library()["tags"].associateBy { it["label"].asText() }

        assertThat(entries["meeting"]!!["transcription_count"].asInt()).isEqualTo(3)
        assertThat(entries["meeting"]!!["sample_titles"].map { it.asText() }).containsExactly("new.mp3", "Named")
        assertThat(entries["unused"]!!["transcription_count"].asInt()).isZero()
    }

    @Test
    fun `the library counts the transcriptions that carry any tag once, and only the user's own`() {
        val a = addTranscription("a.mp3")
        val b = addTranscription("b.mp3")
        addTranscription("untagged.mp3")
        val t1 = createTag("one")
        val t2 = createTag("two")
        post("/transcriptions/$a/tags", """{"tag_id": "$t1"}""")
        post("/transcriptions/$a/tags", """{"tag_id": "$t2"}""")
        post("/transcriptions/$b/tags", """{"tag_id": "$t1"}""")
        val other = newUser()
        createTag("theirs", user = other)

        val body = library()

        assertThat(body["tagged_transcriptions"].asInt()).isEqualTo(2) // a is in two tags but counts once
        assertThat(body["tags"].map { it["label"].asText() }).containsExactly("one", "two")
        assertThat(library(other)["tagged_transcriptions"].asInt()).isZero()
    }

    // ── Editing ────────────────────────────────────────────────────────────────

    @Test
    fun `a tag can be renamed and re-described, together or one at a time`() {
        val id = createTag("meeting", "old text")

        assertThat(put("/tags/$id", """{"label": "Sync", "description": "new text"}""").body!!["label"].asText()).isEqualTo("sync")
        assertThat(library()["tags"].single()["description"].asText()).isEqualTo("new text")

        put("/tags/$id", """{"description": "only text"}""")
        assertThat(library()["tags"].single().let { it["label"].asText() to it["description"].asText() }).isEqualTo("sync" to "only text")

        put("/tags/$id", """{"label": "standup"}""")
        assertThat(library()["tags"].single().let { it["label"].asText() to it["description"].asText() }).isEqualTo("standup" to "only text")
    }

    @Test
    fun `an empty description clears it`() {
        val id = createTag("meeting", "text")

        put("/tags/$id", """{"description": ""}""")

        assertThat(library()["tags"].single().has("description")).isFalse()
    }

    @Test
    fun `renaming keeps the tag on its transcriptions`() {
        val s = addTranscription("a.mp3")
        val id = createTag("meeting")
        post("/transcriptions/$s/tags", """{"tag_id": "$id"}""")

        put("/tags/$id", """{"label": "sync"}""")

        assertThat(tagsOf(s)).containsExactly("sync")
    }

    @Test
    fun `renaming to a name that is taken is a 409, to its own name (in another case) is fine`() {
        createTag("first")
        val second = createTag("second")

        assertThat(put("/tags/$second", """{"label": "FIRST"}""").status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(put("/tags/$second", """{"label": "Second"}""").status).isEqualTo(HttpStatus.OK)
        assertThat(put("/tags/$second", """{"label": "bad name"}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(labels()).containsExactly("first", "second")
    }

    @Test
    fun `someone else's tag, a missing one and a malformed id are all 404`() {
        val theirs = createTag("theirs", user = newUser())

        assertThat(put("/tags/$theirs", """{"label": "mine"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/tags/$theirs").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(put("/tags/${UUID.randomUUID()}", """{"label": "x"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/tags/nope").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(labels()).isEmpty()
    }

    // ── Deleting ───────────────────────────────────────────────────────────────

    @Test
    fun `deleting a tag takes it off every transcription and says how many that was`() {
        val a = addTranscription("a.mp3")
        val b = addTranscription("b.mp3")
        val id = createTag("meeting")
        createTag("keep")
        post("/transcriptions/$a/tags", """{"tag_id": "$id"}""")
        post("/transcriptions/$b/tags", """{"tag_id": "$id"}""")

        val reply = delete("/tags/$id")

        assertThat(reply.status).isEqualTo(HttpStatus.OK)
        assertThat(reply.body!!["affected_transcriptions"].asInt()).isEqualTo(2)
        assertThat(labels()).containsExactly("keep")
        assertThat(tagsOf(a)).isEmpty()
        // The transcriptions themselves are untouched.
        assertThat(get("/transcriptions/$a").status).isEqualTo(HttpStatus.OK)
        assertThat(get("/transcriptions/$b").status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `deleting an unused tag affects nothing`() {
        val id = createTag("unused")

        assertThat(delete("/tags/$id").body!!["affected_transcriptions"].asInt()).isZero()
    }

    // ── On a transcription ─────────────────────────────────────────────────────

    @Test
    fun `a tag of the library is put on a transcription by its id, once`() {
        val s = addTranscription("a.mp3")
        val id = createTag("meeting")

        val first = post("/transcriptions/$s/tags", """{"tag_id": "$id"}""")
        val again = post("/transcriptions/$s/tags", """{"tag_id": "$id"}""")

        assertThat(first.status).isEqualTo(HttpStatus.OK)
        assertThat(first.body!!["id"].asText()).isEqualTo(id.toString())
        assertThat(first.body["color"].asText()).isEqualTo(TagColors.of("meeting"))
        assertThat(again.status).isEqualTo(HttpStatus.OK)
        assertThat(tagsOf(s)).containsExactly("meeting")
        assertThat(library()["tags"].single()["transcription_count"].asInt()).isEqualTo(1)
    }

    @Test
    fun `a new label creates the tag in the library, an existing one is reused`() {
        val s = addTranscription("a.mp3")

        val created = post("/transcriptions/$s/tags", """{"label": "Fresh", "description": "made here"}""")
        val reused = post("/transcriptions/${addTranscription("b.mp3")}/tags", """{"label": "fresh"}""")

        assertThat(created.body!!["label"].asText()).isEqualTo("fresh")
        assertThat(reused.body!!["id"].asText()).isEqualTo(created.body["id"].asText())
        assertThat(library()["tags"].single().let { it["transcription_count"].asInt() to it["description"].asText() })
            .isEqualTo(2 to "made here")
    }

    @Test
    fun `putting a tag on needs a tag id or a valid label`() {
        val s = addTranscription("a.mp3")

        assertThat(post("/transcriptions/$s/tags", "{}").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(post("/transcriptions/$s/tags", """{"label": "bad name"}""").status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(labels()).isEmpty()
    }

    @Test
    fun `a transcription that is not yours, does not exist, or has no transcript yet cannot be tagged`() {
        val tag = createTag("meeting")
        val theirs = addTranscription("theirs.mp3", owner = newUser())
        val pending = addTranscription("pending.mp3", transcript = false)

        assertThat(post("/transcriptions/$theirs/tags", """{"tag_id": "$tag"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("/transcriptions/${UUID.randomUUID()}/tags", """{"tag_id": "$tag"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("/transcriptions/nope/tags", """{"tag_id": "$tag"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("/transcriptions/$pending/tags", """{"tag_id": "$tag"}""").status).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `someone else's tag cannot be put on your transcription`() {
        val mine = addTranscription("mine.mp3")
        val theirs = createTag("theirs", user = newUser())

        assertThat(post("/transcriptions/$mine/tags", """{"tag_id": "$theirs"}""").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(tagsOf(mine)).isEmpty()
    }

    @Test
    fun `a tag is taken off one transcription only, and stays in the library`() {
        val a = addTranscription("a.mp3")
        val b = addTranscription("b.mp3")
        val id = createTag("meeting")
        post("/transcriptions/$a/tags", """{"tag_id": "$id"}""")
        post("/transcriptions/$b/tags", """{"tag_id": "$id"}""")

        assertThat(delete("/transcriptions/$a/tags/$id").status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(delete("/transcriptions/$a/tags/$id").status).isEqualTo(HttpStatus.NO_CONTENT) // already off: not an error

        assertThat(tagsOf(a)).isEmpty()
        assertThat(tagsOf(b)).containsExactly("meeting")
        assertThat(labels()).containsExactly("meeting")
    }

    @Test
    fun `taking a tag off checks whose transcription and tag they are`() {
        val theirsTranscription = addTranscription("theirs.mp3", owner = newUser())
        val mine = addTranscription("mine.mp3")
        val theirsTag = createTag("theirs", user = newUser())
        val myTag = createTag("mine")

        assertThat(delete("/transcriptions/$theirsTranscription/tags/$myTag").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/transcriptions/$mine/tags/$theirsTag").status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(delete("/transcriptions/$mine/tags/nope").status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── Tags in the list and the detail ────────────────────────────────────────

    @Test
    fun `the paged list and the detail describe tags with id, label and the same color as the library`() {
        val s = addTranscription("a.mp3")
        val id = createTag("meeting")
        post("/transcriptions/$s/tags", """{"tag_id": "$id"}""")
        val expected = library()["tags"].single()

        val inList = get("/transcriptions?page=0&size=10").body!!["items"][0]["tags"].single()
        val inDetail = get("/transcriptions/$s").body!!["tags"].single()

        for (tag in listOf(inList, inDetail)) {
            assertThat(tag["id"].asText()).isEqualTo(expected["id"].asText())
            assertThat(tag["label"].asText()).isEqualTo("meeting")
            assertThat(tag["color"].asText()).isEqualTo(expected["color"].asText())
        }
    }

    @Test
    fun `the plain array still names its tags, for the old client`() {
        val s = addTranscription("a.mp3")
        post("/transcriptions/$s/tags", """{"label": "meeting"}""")

        assertThat(get("/transcriptions").body!![0]["tags"].map { it.asText() }).containsExactly("meeting")
    }
}
