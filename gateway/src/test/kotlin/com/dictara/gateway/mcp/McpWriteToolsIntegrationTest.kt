package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import java.util.UUID

/**
 * The tools that change things, over real HTTP and a real database: what a write token is offered, that a read token
 * is not, each tool's effect (checked in the data and through the read tools), the refusals, and that a user can
 * never touch another's data.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpWriteToolsIntegrationTest : AbstractMcpIntegrationTest() {

    private val phrases = listOf(
        Phrase(0.0, 5.0, "SPEAKER_00", "Good morning everyone."),
        Phrase(6.0, 10.0, "SPEAKER_01", "Revenue is up eighteen percent."),
        Phrase(11.0, 15.0, "SPEAKER_00", "Mostly from enterprise."),
        Phrase(16.0, 20.0, "SPEAKER_01", "Churn stayed flat."),
    )

    private class Mine(val user: UUID, val token: String, val submission: UUID)

    private fun mine(title: String = "Daily Standup"): Mine {
        val user = newUser()
        return Mine(user, mint(user, McpAccess.WRITE, "writer"), seed(user, title, phrases, summary = "Old summary"))
    }

    private fun Mine.call(tool: String, arguments: String) = call(token, tool, arguments)

    private fun transcriptOf(m: Mine) = call(m.token, "get_transcript", """{"id":"${m.submission}"}""").data
    private fun infoOf(m: Mine) = call(m.token, "get_transcription", """{"id":"${m.submission}"}""").data
    private fun labels(node: com.fasterxml.jackson.databind.JsonNode) = node.map { it.asText() }

    // -- who is offered what ---------------------------------------------------------------------------------

    @Test
    fun `a write token is offered the write tools, marked as changing things, and a read token is not`() {
        val user = newUser()
        val write = mint(user, McpAccess.WRITE)
        val read = mint(user, McpAccess.READ)

        val writeTools = rpc(write, "tools/list").body!!["result"]["tools"]
        val readTools = rpc(read, "tools/list").body!!["result"]["tools"].map { it["name"].asText() }

        val names = writeTools.map { it["name"].asText() }
        assertThat(names).contains(
            "create_tag", "update_tag", "delete_tag", "create_speaker", "update_speaker", "delete_speaker",
            "add_tag_to_transcription", "remove_tag_from_transcription", "rename_transcription", "set_summary", "edit_phrases",
            "set_voice_speaker", "set_phrase_speaker", "accept_suggestion", "reject_suggestion", "accept_all_suggestions",
            "dismiss_all_suggestions", "whoami", "get_transcript",
        )
        writeTools.filter { it["name"].asText() in names && it["annotations"]["readOnlyHint"].asBoolean().not() }.forEach {
            assertThat(it["description"].asText()).isNotBlank()
            val destructive = it["annotations"]["destructiveHint"].asBoolean()
            assertThat(destructive).`as`(it["name"].asText()).isEqualTo(it["name"].asText() in setOf("delete_tag", "delete_speaker"))
        }
        assertThat(readTools).containsExactlyInAnyOrder("whoami", "list_tags", "list_speakers", "search_transcriptions", "get_transcription", "get_transcript")
    }

    @Test
    fun `a read token cannot use a write tool and nothing changes`() {
        val user = newUser()
        val read = mint(user, McpAccess.READ)

        val refused = call(read, "create_tag", """{"label":"nope"}""")

        assertThat(refused.isError).isTrue()
        assertThat(refused.text).contains("read-only")
        assertThat(auditRows(read).last()["outcome"]).isEqualTo("denied")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tags WHERE user_id = ?", Int::class.java, user)).isZero()
    }

    // -- tags and speakers of the library --------------------------------------------------------------------

    @Test
    fun `a tag is created, renamed, described and deleted, and deleting takes it off the transcriptions`() {
        val m = mine()

        val created = m.call("create_tag", """{"label":"  Meeting ","description":"Regular meetings"}""")
        assertThat(created.isError).isFalse()
        val id = created.data["id"].asText()
        assertThat(created.data["label"].asText()).isEqualTo("meeting") // stored in the one form the app uses
        assertThat(created.data["description"].asText()).isEqualTo("Regular meetings")
        assertThat(m.call("create_tag", """{"label":"meeting"}""").text).contains("already exists")
        assertThat(m.call("create_tag", """{"label":"two words"}""").text).contains("A tag name is")

        assertThat(m.call("update_tag", """{"id":"$id","label":"standup"}""").data["label"].asText()).isEqualTo("standup")
        val cleared = m.call("update_tag", """{"id":"$id","description":""}""").data
        assertThat(cleared["label"].asText()).isEqualTo("standup") // what is left out stays
        assertThat(cleared.has("description") && !cleared["description"].isNull).isFalse()
        assertThat(m.call("update_tag", """{"id":"$id"}""").text).contains("Give a new")

        m.call("add_tag_to_transcription", """{"id":"${m.submission}","tag":"standup"}""")
        val deleted = m.call("delete_tag", """{"id":"$id"}""")
        assertThat(deleted.data["affected_transcriptions"].asInt()).isEqualTo(1)
        assertThat(labels(infoOf(m)["tags"])).isEmpty()
        assertThat(call(m.token, "list_tags").data).isEmpty()
        assertThat(m.call("delete_tag", """{"id":"$id"}""").text).contains("Tag not found")
    }

    @Test
    fun `a speaker is created, renamed and deleted, and deleting sends their voices back to nobody`() {
        val m = mine()
        val maria = m.call("create_speaker", """{"name":"Maria  Chen","description":"Head of Engineering"}""").data
        assertThat(maria["name"].asText()).isEqualTo("Maria Chen")
        assertThat(m.call("create_speaker", """{"name":"maria chen"}""").text).contains("already exists")

        m.call("set_voice_speaker", """{"id":"${m.submission}","voice":"SPEAKER_00","speaker":"Maria Chen"}""")
        assertThat(m.call("update_speaker", """{"id":"${maria["id"].asText()}","name":"Maria C."}""").data["name"].asText()).isEqualTo("Maria C.")
        assertThat(transcriptOf(m)["phrases"][0]["speaker"].asText()).isEqualTo("Maria C.")

        val deleted = m.call("delete_speaker", """{"id":"${maria["id"].asText()}"}""")
        assertThat(deleted.data["affected_transcriptions"].asInt()).isEqualTo(1)
        assertThat(transcriptOf(m)["phrases"][0]["speaker"].asText()).isEqualTo("Speaker 1")
    }

    // -- tags on a transcription -----------------------------------------------------------------------------

    @Test
    fun `a tag of the library is put on and taken off a transcription, and an unknown tag is not made up`() {
        val m = mine()
        tag(m.user, "meeting")

        val on = m.call("add_tag_to_transcription", """{"id":"${m.submission}","tag":"MEETING"}""")
        assertThat(on.data["on_transcription"].asBoolean()).isTrue()
        assertThat(m.call("add_tag_to_transcription", """{"id":"${m.submission}","tag":"meeting"}""").isError).isFalse() // twice is fine
        assertThat(labels(infoOf(m)["tags"])).containsExactly("meeting")

        val typo = m.call("add_tag_to_transcription", """{"id":"${m.submission}","tag":"meetting"}""")
        assertThat(typo.isError).isTrue()
        assertThat(typo.text).contains("create_tag")
        assertThat(call(m.token, "list_tags").data.map { it["label"].asText() }).containsExactly("meeting") // not created

        val off = m.call("remove_tag_from_transcription", """{"id":"${m.submission}","tag":"meeting"}""")
        assertThat(off.data["on_transcription"].asBoolean()).isFalse()
        assertThat(labels(infoOf(m)["tags"])).isEmpty()
    }

    // -- title, summary, text --------------------------------------------------------------------------------

    @Test
    fun `a transcription is renamed, and an empty or overlong title is refused`() {
        val m = mine()

        val renamed = m.call("rename_transcription", """{"id":"${m.submission}","title":"  Q2   review  "}""")

        assertThat(renamed.data["title"].asText()).isEqualTo("Q2 review")
        assertThat(infoOf(m)["title"].asText()).isEqualTo("Q2 review")
        assertThat(m.call("rename_transcription", """{"id":"${m.submission}","title":"   "}""").isError).isTrue()
        assertThat(m.call("rename_transcription", """{"id":"${m.submission}","title":"${"x".repeat(201)}"}""").text).contains("at most 200")
        assertThat(infoOf(m)["title"].asText()).isEqualTo("Q2 review")
    }

    @Test
    fun `the summary is rewritten, the old one is handed back, and it is refused while the summary is being regenerated`() {
        val m = mine()

        val written = m.call("set_summary", """{"id":"${m.submission}","summary":"**Итоги**\n• Выручка выросла на 18%."}""")

        assertThat(written.data["before"].asText()).isEqualTo("Old summary")
        assertThat(infoOf(m)["summary"].asText()).isEqualTo("**Итоги**\n• Выручка выросла на 18%.")
        assertThat(m.call("set_summary", """{"id":"${m.submission}","summary":"  "}""").isError).isTrue()
        assertThat(m.call("set_summary", """{"id":"${m.submission}","summary":"${"x".repeat(20_001)}"}""").text).contains("at most")

        jdbcTemplate.update("UPDATE submissions SET summary_regeneration = 'running' WHERE id = ?", m.submission)
        assertThat(m.call("set_summary", """{"id":"${m.submission}","summary":"Later"}""").text).contains("being regenerated")
        assertThat(infoOf(m)["summary"].asText()).startsWith("**Итоги**")
    }

    @Test
    fun `a transcription with no summary gets one, and one that is not finished is refused`() {
        val user = newUser()
        val token = mint(user, McpAccess.WRITE)
        val bare = seed(user, "No summary yet", phrases, summary = null)
        val working = seed(user, "Still going", phrases, status = "pending")

        val written = call(token, "set_summary", """{"id":"$bare","summary":"Written by hand."}""")

        assertThat(written.data.has("before")).isFalse()
        assertThat(call(token, "get_transcription", """{"id":"$bare"}""").data["summary"].asText()).isEqualTo("Written by hand.")
        assertThat(call(token, "set_summary", """{"id":"$working","summary":"Too early"}""").text).contains("not ready")
    }

    @Test
    fun `phrases are corrected all or none, keeping time and voice, and each replaced text is handed back`() {
        val m = mine()

        val done = m.call("edit_phrases", """{"id":"${m.submission}","edits":[{"index":1,"text":" Revenue is up eighteen percent, year on year. "},{"index":3,"text":"Churn stayed flat — Тест."}]}""")

        assertThat(done.isError).isFalse()
        assertThat(done.data.map { it["before"].asText() }).containsExactly("Revenue is up eighteen percent.", "Churn stayed flat.")
        val after = transcriptOf(m)["phrases"]
        assertThat(after[1]["text"].asText()).isEqualTo("Revenue is up eighteen percent, year on year.")
        assertThat(after[3]["text"].asText()).isEqualTo("Churn stayed flat — Тест.")
        assertThat(after[1]["start_s"].asDouble()).isEqualTo(6.0) // only the text changed
        assertThat(after[1]["voice"].asText()).isEqualTo("SPEAKER_01")
        val cell = jdbcTemplate.queryForObject("SELECT segments -> 1 ->> 'text' FROM transcripts WHERE submission_id = ?", String::class.java, m.submission)
        assertThat(cell).isEqualTo("Revenue is up eighteen percent, year on year.") // the old JSON cell is kept in step

        val bad = m.call("edit_phrases", """{"id":"${m.submission}","edits":[{"index":0,"text":"Changed?"},{"index":99,"text":"No such phrase"}]}""")
        assertThat(bad.isError).isTrue()
        assertThat(bad.text).contains("Phrase not found")
        assertThat(transcriptOf(m)["phrases"][0]["text"].asText()).isEqualTo("Good morning everyone.") // nothing of the batch applied
        assertThat(m.call("edit_phrases", """{"id":"${m.submission}","edits":[{"index":0,"text":"  "}]}""").isError).isTrue()
        assertThat(m.call("edit_phrases", """{"id":"${m.submission}","edits":[]}""").isError).isTrue()
        assertThat(m.call("edit_phrases", """{"id":"${m.submission}","edits":[{"index":0}]}""").text).contains("string 'text'")
        val many = (0..100).joinToString(",") { """{"index":0,"text":"x"}""" }
        assertThat(m.call("edit_phrases", """{"id":"${m.submission}","edits":[$many]}""").text).contains("At most")
    }

    // -- who speaks ------------------------------------------------------------------------------------------

    @Test
    fun `a voice is made a speaker of the library, single phrases can be reassigned, and a made-up speaker is refused`() {
        val m = mine()
        val maria = speaker(m.user, "Maria Chen")
        speaker(m.user, "Alex")

        val voices = infoOf(m)["voices"]
        assertThat(voices.map { it["voice"].asText() }).containsExactly("SPEAKER_00", "SPEAKER_01")
        assertThat(voices[0].has("speaker")).isFalse()

        val set = m.call("set_voice_speaker", """{"id":"${m.submission}","voice":"SPEAKER_00","speaker_id":"$maria"}""")
        assertThat(set.data["speaker"].asText()).isEqualTo("Maria Chen")
        assertThat(set.data["phrases"].asInt()).isEqualTo(2)
        assertThat(transcriptOf(m)["phrases"].map { it["speaker"].asText() }).containsExactly("Maria Chen", "Speaker 2", "Maria Chen", "Speaker 2")
        assertThat(infoOf(m)["voices"][0]["speaker"].asText()).isEqualTo("Maria Chen")

        val one = m.call("set_phrase_speaker", """{"id":"${m.submission}","phrases":[3],"speaker":"alex"}""")
        assertThat(one.data["speaker"].asText()).isEqualTo("Alex")
        assertThat(transcriptOf(m)["phrases"].map { it["speaker"].asText() }).containsExactly("Maria Chen", "Speaker 2", "Maria Chen", "Alex")

        val missing = m.call("set_voice_speaker", """{"id":"${m.submission}","voice":"SPEAKER_01","speaker":"Nobody I know"}""")
        assertThat(missing.text).contains("create_speaker")
        assertThat(m.call("set_voice_speaker", """{"id":"${m.submission}","voice":"SPEAKER_07","speaker":"Alex"}""").text).contains("SPEAKER_00")
        assertThat(m.call("set_voice_speaker", """{"id":"${m.submission}","voice":"SPEAKER_01"}""").text).contains("'speaker'")
        assertThat(m.call("set_phrase_speaker", """{"id":"${m.submission}","phrases":[42],"speaker":"Alex"}""").isError).isTrue()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM speakers WHERE user_id = ?", Int::class.java, m.user)).isEqualTo(2)
    }

    // -- the AI's proposals ----------------------------------------------------------------------------------

    private fun propose(m: Mine, title: String? = "Proposed title", tagLabel: String? = "meeting", voice: String? = "SPEAKER_01", name: String = "Dana") {
        val transcript = jdbcTemplate.queryForObject("SELECT id FROM transcripts WHERE submission_id = ?", UUID::class.java, m.submission)
        if (title != null) jdbcTemplate.update("INSERT INTO transcript_suggestions(transcript_id, kind, title) VALUES (?, 'title', ?)", transcript, title)
        if (tagLabel != null) {
            val tagId = jdbcTemplate.queryForList("SELECT id FROM tags WHERE user_id = ? AND name = ?", UUID::class.java, m.user, tagLabel).firstOrNull() ?: tag(m.user, tagLabel)
            jdbcTemplate.update("INSERT INTO transcript_suggestions(transcript_id, kind, tag_id) VALUES (?, 'tag', ?)", transcript, tagId)
        }
        if (voice != null) {
            jdbcTemplate.update("INSERT INTO transcript_suggestions(transcript_id, kind, voice, speaker_name) VALUES (?, 'speaker', ?, ?)", transcript, voice, name)
        }
    }

    @Test
    fun `proposals are shown, and each can be accepted or rejected on its own`() {
        val m = mine()
        propose(m)

        val shown = infoOf(m)["suggestions"]
        assertThat(shown["title"].asText()).isEqualTo("Proposed title")
        assertThat(labels(shown["tags"])).containsExactly("meeting")
        assertThat(shown["speakers"][0]["voice"].asText()).isEqualTo("SPEAKER_01")

        val title = m.call("accept_suggestion", """{"id":"${m.submission}","kind":"title"}""")
        assertThat(title.data["left"].has("title")).isFalse() // the title is settled, the rest is still open
        assertThat(infoOf(m)["title"].asText()).isEqualTo("Proposed title")

        val tag = m.call("accept_suggestion", """{"id":"${m.submission}","kind":"tag","value":"meeting"}""")
        assertThat(labels(infoOf(m)["tags"])).containsExactly("meeting")
        assertThat(tag.data["left"]["tags"]).isEmpty()

        val speaker = m.call("accept_suggestion", """{"id":"${m.submission}","kind":"speaker","value":"SPEAKER_01"}""")
        assertThat(speaker.data.has("left")).isFalse() // nothing left
        assertThat(infoOf(m)["voices"][1]["speaker"].asText()).isEqualTo("Dana") // created in the library on accept
        assertThat(infoOf(m).has("suggestions")).isFalse()

        assertThat(m.call("accept_suggestion", """{"id":"${m.submission}","kind":"title"}""").text).contains("No such suggestion")
    }

    @Test
    fun `a rejected proposal changes nothing and is not offered again`() {
        val m = mine()
        propose(m)

        m.call("reject_suggestion", """{"id":"${m.submission}","kind":"title"}""")
        m.call("reject_suggestion", """{"id":"${m.submission}","kind":"tag","value":"meeting"}""")
        m.call("reject_suggestion", """{"id":"${m.submission}","kind":"speaker","value":"SPEAKER_01"}""")

        val info = infoOf(m)
        assertThat(info["title"].asText()).isEqualTo("Daily Standup")
        assertThat(labels(info["tags"])).isEmpty()
        assertThat(info["voices"][1].has("speaker")).isFalse()
        assertThat(info.has("suggestions")).isFalse()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM speakers WHERE user_id = ?", Int::class.java, m.user)).isZero()
        assertThat(m.call("accept_suggestion", """{"id":"${m.submission}","kind":"tag"}""").text).contains("'value' is required")
        assertThat(m.call("accept_suggestion", """{"id":"${m.submission}","kind":"mood","value":"x"}""").text).contains("'kind' is one of")
    }

    @Test
    fun `all proposals are accepted at once, or all turned down`() {
        val a = mine("First")
        val b = mine("Second")
        propose(a)
        propose(b)

        val accepted = a.call("accept_all_suggestions", """{"id":"${a.submission}"}""")
        val dismissed = b.call("dismiss_all_suggestions", """{"id":"${b.submission}"}""")

        assertThat(accepted.data.has("left")).isFalse()
        assertThat(infoOf(a)["title"].asText()).isEqualTo("Proposed title")
        assertThat(labels(infoOf(a)["tags"])).containsExactly("meeting")
        assertThat(infoOf(a)["voices"][1]["speaker"].asText()).isEqualTo("Dana")
        assertThat(dismissed.data.has("left")).isFalse()
        assertThat(infoOf(b)["title"].asText()).isEqualTo("Second")
        assertThat(labels(infoOf(b)["tags"])).isEmpty()
    }

    // -- one user never reaches another's data ---------------------------------------------------------------

    @Test
    fun `no write tool reaches another user's transcription, tag or speaker, and nothing of theirs changes`() {
        val alice = mine("Alice's call")
        val aliceTag = tag(alice.user, "private")
        val aliceSpeaker = speaker(alice.user, "Alice")
        propose(alice)
        val bob = mine("Bob's call")
        val id = alice.submission
        val notFound = "not found"

        val attempts = listOf(
            "rename_transcription" to """{"id":"$id","title":"Hacked"}""",
            "set_summary" to """{"id":"$id","summary":"Hacked"}""",
            "edit_phrases" to """{"id":"$id","edits":[{"index":0,"text":"Hacked"}]}""",
            "add_tag_to_transcription" to """{"id":"$id","tag":"mine"}""",
            "remove_tag_from_transcription" to """{"id":"$id","tag":"mine"}""",
            "set_voice_speaker" to """{"id":"$id","voice":"SPEAKER_00","speaker":"Bobby"}""",
            "set_phrase_speaker" to """{"id":"$id","phrases":[0],"speaker":"Bobby"}""",
            "accept_suggestion" to """{"id":"$id","kind":"title"}""",
            "reject_suggestion" to """{"id":"$id","kind":"title"}""",
            "accept_all_suggestions" to """{"id":"$id"}""",
            "dismiss_all_suggestions" to """{"id":"$id"}""",
            "update_tag" to """{"id":"$aliceTag","label":"hacked"}""",
            "delete_tag" to """{"id":"$aliceTag"}""",
            "update_speaker" to """{"id":"$aliceSpeaker","name":"Hacked"}""",
            "delete_speaker" to """{"id":"$aliceSpeaker"}""",
        )
        call(bob.token, "create_tag", """{"label":"mine"}""")
        call(bob.token, "create_speaker", """{"name":"Bobby"}""")

        for ((tool, args) in attempts) {
            val reply = call(bob.token, tool, args)
            assertThat(reply.isError).`as`(tool).isTrue()
            assertThat(reply.text).`as`(tool).containsIgnoringCase(notFound)
        }

        val info = infoOf(alice)
        assertThat(info["title"].asText()).isEqualTo("Alice's call")
        assertThat(info["summary"].asText()).isEqualTo("Old summary")
        assertThat(transcriptOf(alice)["phrases"][0]["text"].asText()).isEqualTo("Good morning everyone.")
        assertThat(call(alice.token, "list_tags").data.map { it["label"].asText() }).containsExactlyInAnyOrder("private", "meeting")
        assertThat(call(alice.token, "list_speakers").data.map { it["name"].asText() }).containsExactly("Alice")
        assertThat(info.has("suggestions")).isTrue()
    }

    @Test
    fun `a bad id, a missing argument and a wrong type are answered as errors, and every call is written down`() {
        val m = mine()

        assertThat(m.call("rename_transcription", """{"id":"not-an-id","title":"x"}""").text).contains("not found")
        assertThat(m.call("rename_transcription", """{"id":"${m.submission}"}""").text).contains("'title' is required")
        assertThat(m.call("create_tag", """{"label":5}""").text).contains("'label' must be a string")
        assertThat(m.call("set_phrase_speaker", """{"id":"${m.submission}","phrases":"0","speaker":"x"}""").text).contains("list of integers")
        m.call("create_tag", """{"label":"fine"}""")

        assertThat(auditRows(m.token).map { it["tool"] to it["outcome"] }).containsExactly(
            "rename_transcription" to "error", "rename_transcription" to "error", "create_tag" to "error",
            "set_phrase_speaker" to "error", "create_tag" to "ok",
        )
    }
}
