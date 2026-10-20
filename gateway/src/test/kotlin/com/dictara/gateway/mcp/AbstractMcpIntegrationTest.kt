package com.dictara.gateway.mcp

import com.dictara.gateway.AbstractSharedContextIntegrationTest
import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.token.McpTokenService
import com.dictara.gateway.service.JwtTokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import com.dictara.gateway.storage.AudioStorage
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID

/** Wiring and helpers shared by the MCP integration tests: users, tokens, JSON-RPC calls and some transcriptions. */
abstract class AbstractMcpIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var tokens: McpTokenService
    @MockBean lateinit var audioStorage: AudioStorage

    protected val json = ObjectMapper()

    protected class Reply(val status: Int, val body: JsonNode?, val headers: HttpHeaders)

    /** A user on [plan]: Ultra (`unlimited`) by default, the plan that includes MCP access, so that tokens can be made. */
    protected fun newUser(plan: String = "unlimited"): UUID =
        UUID.randomUUID().also { jdbcTemplate.update("INSERT INTO users(id, plan) VALUES (?, ?)", it, plan) }

    /** A working token of [user] and its secret. */
    protected fun mint(user: UUID, access: McpAccess = McpAccess.READ, name: String = "test token") = tokens.create(user, name, access).secret

    protected fun http(method: HttpMethod, path: String, bearer: String?, body: String? = null): Reply {
        val headers = HttpHeaders().apply {
            bearer?.let { setBearerAuth(it) }
            contentType = MediaType.APPLICATION_JSON
        }
        val response = rest.exchange(path, method, HttpEntity(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(response.statusCode.value(), text?.let(json::readTree), response.headers)
    }

    /** A request of the app: the session JWT of [user]. */
    protected fun session(method: HttpMethod, path: String, user: UUID, body: String? = null) = http(method, path, jwt.issue(user), body)

    protected var nextId = 1

    /** One JSON-RPC request to `/mcp` with [token] as the bearer. */
    protected fun rpc(token: String?, method: String, params: String = "{}"): Reply =
        http(HttpMethod.POST, "/mcp", token, """{"jsonrpc":"2.0","id":${nextId++},"method":"$method","params":$params}""")

    /** The result of a `tools/call`: the content of its one text block, and whether it is an error. */
    protected class ToolReply(val text: String, val isError: Boolean) {
        val data: JsonNode get() = ObjectMapper().readTree(text)
    }

    protected fun call(token: String, tool: String, arguments: String = "{}"): ToolReply {
        val reply = rpc(token, "tools/call", """{"name":"$tool","arguments":$arguments}""")
        check(reply.status == 200) { "tools/call answered ${reply.status}: ${reply.body}" }
        val result = reply.body!!["result"] ?: error("no result: ${reply.body}")
        return ToolReply(result["content"][0]["text"].asText(), result["isError"]?.asBoolean() ?: false)
    }

    protected class Phrase(val start: Double, val end: Double, val voice: String?, val text: String)

    /** A finished transcription of [owner] with these phrases, a summary, and returns its id (the job id). */
    protected fun seed(
        owner: UUID,
        title: String,
        phrases: List<Phrase>,
        createdAt: Instant = Instant.parse("2026-06-22T10:00:00Z"),
        summary: String? = "Summary of $title",
        status: String = "done",
    ): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, ?, 'audio/mp4', 1000, ?, ?, ?)",
            audioId, owner, "$title.m4a", "gs://test/$audioId", "hash-$audioId", phrases.maxOfOrNull { it.end } ?: 0.0,
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, created_at) VALUES (?, ?, ?, 'fast', 'auto', true, 'auto', 'web', ?, ?)",
            submissionId, owner, audioId, status, java.sql.Timestamp.from(createdAt),
        )
        val segments = json.writeValueAsString(
            phrases.map { mapOf("start" to it.start, "end" to it.end, "text" to it.text, "speaker" to it.voice) },
        )
        insertTranscript(transcriptId, submissionId, segments, title = title, detectedLanguage = "en")
        if (summary != null) {
            jdbcTemplate.update("INSERT INTO summaries(submission_id, text) VALUES (?, ?)", submissionId, summary)
        }
        return submissionId
    }

    protected fun tag(owner: UUID, name: String, description: String? = null): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO tags(id, user_id, name, description) VALUES (?, ?, ?, ?)", id, owner, name, description)
        return id
    }

    protected fun tagOn(submission: UUID, tag: UUID) {
        jdbcTemplate.update(
            "INSERT INTO transcript_tags(transcript_id, tag_id) SELECT id, ? FROM transcripts WHERE submission_id = ?", tag, submission,
        )
    }

    protected fun speaker(owner: UUID, name: String, description: String? = null): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO speakers(id, user_id, name, description) VALUES (?, ?, ?, ?)", id, owner, name, description)
        return id
    }

    protected fun voiceIs(submission: UUID, voice: String, speaker: UUID) {
        jdbcTemplate.update(
            "INSERT INTO transcript_voices(transcript_id, voice, speaker_id) SELECT id, ?, ? FROM transcripts WHERE submission_id = ?",
            voice, speaker, submission,
        )
    }

    protected fun auditRows(token: String): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        """SELECT a.tool, a.outcome FROM mcp.audit_log a JOIN mcp.access_tokens t ON t.id = a.token_id
           WHERE t.token_hash = ? ORDER BY a.id""",
        McpTokenService.hash(token),
    )
}
