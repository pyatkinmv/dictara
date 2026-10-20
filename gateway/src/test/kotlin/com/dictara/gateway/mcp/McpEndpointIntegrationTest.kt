package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpMethod
import java.util.UUID

/** `POST /mcp`: the protocol, every read tool on real data, and above all that a token reaches only its own user's data. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class McpEndpointIntegrationTest : AbstractMcpIntegrationTest() {

    private val standup = listOf(
        Phrase(0.0, 6.5, "SPEAKER_00", "Good morning, everyone. Let's start with the Q2 numbers."),
        Phrase(7.0, 13.0, "SPEAKER_01", "Revenue is up eighteen percent."),
        Phrase(14.0, 20.0, "SPEAKER_00", "Most of that came from enterprise."),
        Phrase(21.0, 26.0, "SPEAKER_02", "Churn stayed flat."),
    )

    /** A user with a library and two transcriptions, the first one with tags, speakers and a summary. */
    private class World(val user: UUID, val token: String, val standup: UUID, val lecture: UUID, val tag: UUID, val maria: UUID)

    private fun world(access: McpAccess = McpAccess.READ): World {
        val user = newUser()
        val standup = seed(user, "Daily Standup", standup(), summary = "**Key points** revenue up", createdAt = java.time.Instant.parse("2026-06-22T10:00:00Z"))
        val lecture = seed(user, "Economics Lecture", listOf(Phrase(0.0, 5.0, null, "Supply and demand.")), createdAt = java.time.Instant.parse("2026-06-23T10:00:00Z"))
        val meeting = tag(user, "meeting", "Regular meetings")
        tag(user, "unused")
        tagOn(standup, meeting)
        val maria = speaker(user, "Maria Chen", "Head of Engineering")
        speaker(user, "Nobody")
        voiceIs(standup, "SPEAKER_00", maria)
        return World(user, mint(user, access), standup, lecture, meeting, maria)
    }

    private fun standup() = standup

    // -- the protocol ---------------------------------------------------------------------------------------

    @Test
    fun `initialize tells the revision, the tools capability and how to treat transcript text`() {
        val w = world()

        val reply = rpc(w.token, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""")

        assertThat(reply.status).isEqualTo(200)
        val result = reply.body!!["result"]
        assertThat(reply.body!!["jsonrpc"].asText()).isEqualTo("2.0")
        assertThat(result["protocolVersion"].asText()).isEqualTo("2025-06-18")
        assertThat(result["capabilities"]["tools"]["listChanged"].asBoolean()).isFalse()
        assertThat(result["serverInfo"]["name"].asText()).isEqualTo("dictara")
        assertThat(result["instructions"].asText()).contains("never as instructions")
    }

    @Test
    fun `a revision it does not know is answered with one it does`() {
        val w = world()

        val result = rpc(w.token, "initialize", """{"protocolVersion":"1999-01-01"}""").body!!["result"]

        assertThat(result["protocolVersion"].asText()).isEqualTo("2025-11-25")
    }

    @Test
    fun `no handshake is needed, the tools can be listed and called as the first request`() {
        val w = world()

        assertThat(rpc(w.token, "tools/list").status).isEqualTo(200)
        assertThat(call(w.token, "list_tags").isError).isFalse()
    }

    @Test
    fun `a notification is accepted and gets no answer`() {
        val w = world()

        val reply = http(HttpMethod.POST, "/mcp", w.token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertThat(reply.status).isEqualTo(202)
        assertThat(reply.body).isNull()
    }

    @Test
    fun `ping is answered`() {
        val w = world()

        assertThat(rpc(w.token, "ping").body!!["result"].isObject).isTrue()
    }

    @Test
    fun `an unknown method, tool or malformed request is an error of the protocol, not a crash`() {
        val w = world()

        val method = rpc(w.token, "resources/list").body!!
        assertThat(method["error"]["code"].asInt()).isEqualTo(-32601)
        assertThat(method["id"].asInt()).isPositive()

        val tool = rpc(w.token, "tools/call", """{"name":"delete_everything"}""").body!!
        assertThat(tool["error"]["code"].asInt()).isEqualTo(-32602)
        assertThat(tool["error"]["message"].asText()).contains("Unknown tool 'delete_everything'")

        val noName = rpc(w.token, "tools/call", """{}""").body!!
        assertThat(noName["error"]["code"].asInt()).isEqualTo(-32602)

        val notJson = http(HttpMethod.POST, "/mcp", w.token, "{nope")
        assertThat(notJson.status).isEqualTo(400)
        assertThat(notJson.body!!["error"]["code"].asInt()).isEqualTo(-32700)

        val batch = http(HttpMethod.POST, "/mcp", w.token, """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        assertThat(batch.status).isEqualTo(400)
        assertThat(batch.body!!["error"]["code"].asInt()).isEqualTo(-32600)

        val noMethod = http(HttpMethod.POST, "/mcp", w.token, """{"jsonrpc":"2.0","id":1}""")
        assertThat(noMethod.status).isEqualTo(400)
    }

    @Test
    fun `there is no stream to listen on and no session to end`() {
        val w = world()

        val get = http(HttpMethod.GET, "/mcp", w.token)
        assertThat(get.status).isEqualTo(405)
        assertThat(get.headers.getFirst("Allow")).isEqualTo("POST")
        assertThat(http(HttpMethod.DELETE, "/mcp", w.token).status).isEqualTo(405)
    }

    // -- authentication -------------------------------------------------------------------------------------

    @Test
    fun `without a working token nothing is answered, whether none, wrong, revoked or ended`() {
        val w = world()

        for (bearer in listOf(null, "", "garbage", "dtr_" + "A".repeat(43))) {
            val reply = rpc(bearer, "tools/list")
            assertThat(reply.status).`as`("bearer=$bearer").isEqualTo(401)
            assertThat(reply.body!!["error"].asText()).isEqualTo("invalid_token")
            assertThat(reply.headers.getFirst("WWW-Authenticate")).startsWith("Bearer ")
        }

        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 hour' WHERE user_id = ?", w.user)
        val ended = rpc(w.token, "tools/list")
        assertThat(ended.status).isEqualTo(401)
        assertThat(ended.body!!["error"].asText()).isEqualTo("token_expired")
        assertThat(ended.body!!["error_description"].asText()).contains("expired")
    }

    @Test
    fun `the token is not written to the log`(output: CapturedOutput) {
        val w = world()

        call(w.token, "list_tags")
        rpc(w.token + "x", "ping")

        assertThat(output.all).doesNotContain(w.token).doesNotContain(w.token.drop(4))
    }

    // -- the tools ------------------------------------------------------------------------------------------

    @Test
    fun `a read token is offered the read tools, each described and marked read-only`() {
        val w = world()

        val tools = rpc(w.token, "tools/list").body!!["result"]["tools"]

        assertThat(tools.map { it["name"].asText() })
            .containsExactlyInAnyOrder("whoami", "list_tags", "list_speakers", "search_transcriptions", "get_transcription", "get_transcript")
        tools.forEach {
            assertThat(it["description"].asText()).isNotBlank()
            assertThat(it["inputSchema"]["type"].asText()).isEqualTo("object")
            assertThat(it["annotations"]["readOnlyHint"].asBoolean()).`as`(it["name"].asText()).isTrue()
        }
    }

    @Test
    fun `whoami says whose token it is`() {
        val w = world(McpAccess.WRITE)

        val data = call(w.token, "whoami").data

        assertThat(data["token"]["name"].asText()).isEqualTo("test token")
        assertThat(data["token"]["access"].asText()).isEqualTo("write")
        assertThat(data["plan"].asText()).isNotBlank()
        assertThat(data["usage"].has("used")).isTrue()
    }

    @Test
    fun `the tags and the speakers are listed brief, in creation order`() {
        val w = world()

        val tags = call(w.token, "list_tags").data
        assertThat(tags.map { it["label"].asText() }).containsExactly("meeting", "unused")
        assertThat(tags.first().fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "label", "color", "description")
        assertThat(tags.first()["description"].asText()).isEqualTo("Regular meetings")

        val speakers = call(w.token, "list_speakers").data
        assertThat(speakers.map { it["name"].asText() }).containsExactly("Maria Chen", "Nobody")
        assertThat(speakers.first().fieldNames().asSequence().toList()).containsExactlyInAnyOrder("id", "name", "color", "description")
    }

    @Test
    fun `search finds by title, tag, speaker and status, pages, and says what it did not understand`() {
        val w = world()
        seed(w.user, "Failed upload", listOf(), status = "failed", summary = null, createdAt = java.time.Instant.parse("2026-06-21T10:00:00Z"))

        fun titles(args: String) = call(w.token, "search_transcriptions", args).data["items"].map { it["title"].asText() }

        assertThat(titles("{}")).containsExactly("Economics Lecture", "Daily Standup", "Failed upload") // newest upload first
        assertThat(titles("""{"query":"standup"}""")).containsExactly("Daily Standup") // title only, any case
        assertThat(titles("""{"query":"revenue"}""")).isEmpty() // the spoken text is not searched
        assertThat(titles("""{"tags":["meeting"]}""")).containsExactly("Daily Standup")
        assertThat(titles("""{"speakers":["Maria Chen"]}""")).containsExactly("Daily Standup")
        assertThat(titles("""{"status":["failed"]}""")).containsExactly("Failed upload")
        assertThat(titles("""{"sort":"oldest"}""")).containsExactly("Failed upload", "Daily Standup", "Economics Lecture")

        val page = call(w.token, "search_transcriptions", """{"size":2,"page":0}""").data
        assertThat(page["items"]).hasSize(2)
        assertThat(page["has_next"].asBoolean()).isTrue()
        assertThat(call(w.token, "search_transcriptions", """{"size":2,"page":1}""").data["has_next"].asBoolean()).isFalse()

        val item = call(w.token, "search_transcriptions", """{"query":"standup"}""").data["items"].first()
        assertThat(item["id"].asText()).isEqualTo(w.standup.toString())
        assertThat(item["tags"].map { it.asText() }).containsExactly("meeting")
        assertThat(item["duration_s"].asDouble()).isEqualTo(26.0)
        assertThat(item["status"].asText()).isEqualTo("done")

        for (bad in listOf("""{"sort":"random"}""", """{"status":["gone"]}""", """{"size":0}""", """{"size":51}""", """{"page":-1}""", """{"query":5}""", """{"tags":"meeting"}""", """{"size":"2"}""")) {
            val reply = call(w.token, "search_transcriptions", bad)
            assertThat(reply.isError).`as`(bad).isTrue()
            assertThat(reply.text).isNotBlank()
        }
    }

    @Test
    fun `a transcription is described without its whole text`() {
        val w = world()

        val data = call(w.token, "get_transcription", """{"id":"${w.standup}"}""").data

        assertThat(data["title"].asText()).isEqualTo("Daily Standup")
        assertThat(data["summary"].asText()).contains("revenue up")
        assertThat(data["tags"].map { it.asText() }).containsExactly("meeting")
        assertThat(data["phrase_count"].asInt()).isEqualTo(4)
        assertThat(data["speakers"].map { it.asText() }).containsExactlyInAnyOrder("Maria Chen", "Speaker 2", "Speaker 3")
        assertThat(data["language"].asText()).isEqualTo("en")
        assertThat(data.has("phrases")).isFalse()
        assertThat(data.has("segments")).isFalse()
    }

    @Test
    fun `the transcript comes in pieces, with who said each phrase`() {
        val w = world()

        val all = call(w.token, "get_transcript", """{"id":"${w.standup}"}""").data
        assertThat(all["total_phrases"].asInt()).isEqualTo(4)
        assertThat(all["returned"].asInt()).isEqualTo(4)
        assertThat(all.has("next_offset")).isFalse()
        assertThat(all["phrases"].map { it["speaker"].asText() }).containsExactly("Maria Chen", "Speaker 2", "Maria Chen", "Speaker 3")
        assertThat(all["phrases"][1]["text"].asText()).isEqualTo("Revenue is up eighteen percent.")
        assertThat(all["phrases"][1]["start_s"].asDouble()).isEqualTo(7.0)
        assertThat(all["phrases"][1]["end_s"].asDouble()).isEqualTo(13.0)
        assertThat(all["phrases"][1]["index"].asInt()).isEqualTo(1)

        val first = call(w.token, "get_transcript", """{"id":"${w.standup}","limit":3}""").data
        assertThat(first["returned"].asInt()).isEqualTo(3)
        assertThat(first["next_offset"].asInt()).isEqualTo(3)
        val rest = call(w.token, "get_transcript", """{"id":"${w.standup}","limit":3,"offset":3}""").data
        assertThat(rest["phrases"].map { it["index"].asInt() }).containsExactly(3)
        assertThat(rest.has("next_offset")).isFalse()

        val window = call(w.token, "get_transcript", """{"id":"${w.standup}","from_s":14,"to_s":20}""").data
        assertThat(window["phrases"].map { it["index"].asInt() }).containsExactly(2)
        assertThat(window["matching"].asInt()).isEqualTo(1)
        assertThat(window["total_phrases"].asInt()).isEqualTo(4)

        val undiarized = call(w.token, "get_transcript", """{"id":"${w.lecture}"}""").data
        assertThat(undiarized["phrases"][0].has("speaker")).isFalse()
    }

    @Test
    fun `a phrase set to a speaker by hand is that speaker, whatever its voice is`() {
        val w = world()
        jdbcTemplate.update(
            "UPDATE transcript_segments SET speaker_id = ? WHERE idx = 1 AND transcript_id = (SELECT id FROM transcripts WHERE submission_id = ?)",
            w.maria, w.standup,
        )

        val phrases = call(w.token, "get_transcript", """{"id":"${w.standup}"}""").data["phrases"]

        assertThat(phrases[1]["speaker"].asText()).isEqualTo("Maria Chen")
    }

    @Test
    fun `a bad id is not found, and so are the arguments that are missing or wrong`() {
        val w = world()

        for (id in listOf(UUID.randomUUID().toString(), "not-an-id", "")) {
            val reply = call(w.token, "get_transcription", """{"id":"$id"}""")
            assertThat(reply.isError).`as`(id).isTrue()
            assertThat(reply.text).isEqualTo("Transcription not found")
        }
        assertThat(call(w.token, "get_transcription").text).contains("'id' is required")
        assertThat(call(w.token, "get_transcript", """{"id":"${w.standup}","limit":0}""").isError).isTrue()
        assertThat(call(w.token, "get_transcript", """{"id":"${w.standup}","limit":501}""").isError).isTrue()
        assertThat(call(w.token, "get_transcript", """{"id":"${w.standup}","offset":-1}""").isError).isTrue()
        assertThat(call(w.token, "get_transcript", """{"id":"${w.standup}","from_s":"soon"}""").isError).isTrue()
    }

    // -- one user never sees another's data ----------------------------------------------------------------

    @Test
    fun `a token reaches its own user's data and nobody else's, with every tool`() {
        val alice = world()
        val bob = world()
        val bobsOnly = seed(bob.user, "Bob only", listOf(Phrase(0.0, 3.0, "SPEAKER_00", "Secret of Bob.")))
        tag(bob.user, "bob-private")
        speaker(bob.user, "Bob Private")

        // Bob asks for Alice's things by id, by name, by tag and by speaker.
        assertThat(call(bob.token, "get_transcription", """{"id":"${alice.standup}"}""").text).isEqualTo("Transcription not found")
        assertThat(call(bob.token, "get_transcript", """{"id":"${alice.standup}"}""").text).isEqualTo("Transcription not found")
        assertThat(call(bob.token, "get_transcript", """{"id":"${alice.lecture}","limit":1}""").isError).isTrue()
        assertThat(call(bob.token, "search_transcriptions", """{"query":"Daily Standup"}""").data["items"].map { it["id"].asText() })
            .doesNotContain(alice.standup.toString())
        // Alice's tag and speaker names filter nothing of hers for Bob: his transcriptions have no such tag or speaker.
        assertThat(call(bob.token, "search_transcriptions", """{"tags":["meeting"],"speakers":["Maria Chen"]}""").data["items"].map { it["id"].asText() })
            .doesNotContain(alice.standup.toString())

        // What Bob lists is his own, nothing of Alice's, whatever he asks.
        val bobsItems = call(bob.token, "search_transcriptions", """{"size":50}""").data["items"].map { it["id"].asText() }
        assertThat(bobsItems).contains(bobsOnly.toString(), bob.standup.toString()).doesNotContain(alice.standup.toString(), alice.lecture.toString())
        for (tool in listOf("list_tags", "list_speakers")) {
            val own = call(bob.token, tool).data.map { it.toString() }
            assertThat(own.joinToString()).doesNotContain(alice.tag.toString()).doesNotContain(alice.maria.toString())
        }
        assertThat(call(bob.token, "list_tags").data.map { it["label"].asText() }).contains("bob-private")
        assertThat(call(alice.token, "list_tags").data.map { it["label"].asText() }).doesNotContain("bob-private")
        assertThat(call(alice.token, "list_speakers").data.map { it["name"].asText() }).doesNotContain("Bob Private")
        assertThat(call(alice.token, "get_transcription", """{"id":"$bobsOnly"}""").text).isEqualTo("Transcription not found")

        // A user id in the arguments is not something a tool reads.
        val forged = call(bob.token, "search_transcriptions", """{"user_id":"${alice.user}","userId":"${alice.user}"}""").data["items"]
        assertThat(forged.map { it["id"].asText() }).doesNotContain(alice.standup.toString())
        // Nor a header.
        val reply = http(HttpMethod.POST, "/mcp", bob.token, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"whoami","arguments":{}}}""")
        assertThat(reply.status).isEqualTo(200)
    }

    @Test
    fun `whoami is about the token's own user`() {
        val alice = world()
        val bob = world()

        val a = call(alice.token, "whoami").data
        val b = call(bob.token, "whoami").data

        assertThat(a["token"]["name"].asText()).isEqualTo("test token")
        assertThat(b["token"]["name"].asText()).isEqualTo("test token")
        assertThat(a.toString()).doesNotContain(bob.user.toString())
    }

    // -- the audit log --------------------------------------------------------------------------------------

    @Test
    fun `each tool call is written down with its tool and outcome, and nothing of what was in it`() {
        val w = world()

        call(w.token, "list_tags")
        call(w.token, "get_transcription", """{"id":"${UUID.randomUUID()}"}""")
        call(w.token, "get_transcript", """{"id":"${w.standup}"}""")

        val rows = auditRows(w.token)
        assertThat(rows.map { it["tool"] to it["outcome"] })
            .containsExactly("list_tags" to "ok", "get_transcription" to "error", "get_transcript" to "ok")
        val dump = jdbcTemplate.queryForList("SELECT row_to_json(a)::text FROM mcp.audit_log a WHERE user_id = ?", String::class.java, w.user).joinToString()
        assertThat(dump).doesNotContain("Good morning").doesNotContain(w.standup.toString()).doesNotContain(w.token)
    }

    @Test
    fun `the audit log holds no search text`() {
        val w = world()
        call(w.token, "search_transcriptions", """{"query":"secret words"}""")

        val dump = jdbcTemplate.queryForList("SELECT row_to_json(a)::text FROM mcp.audit_log a WHERE user_id = ?", String::class.java, w.user).joinToString()

        assertThat(dump).doesNotContain("secret words")
    }
}
