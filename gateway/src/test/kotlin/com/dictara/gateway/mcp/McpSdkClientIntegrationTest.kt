package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.http.HttpRequest
import java.time.Duration

/**
 * Our server against a client that is not ours: the official MCP Java SDK, which does what Claude Code or OpenCode do
 * (the handshake, listing tools, calling them, over Streamable HTTP with a bearer header). If our own reading of the
 * protocol were wrong in a way our own tests share, this is where it would show.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpSdkClientIntegrationTest : AbstractMcpIntegrationTest() {

    @LocalServerPort var port: Int = 0

    private fun client(token: String): McpSyncClient {
        val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$port")
            .endpoint("/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $token"))
            .build()
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).initializationTimeout(Duration.ofSeconds(15)).build()
    }

    private fun McpSchema.CallToolResult.text() = (content().first() as McpSchema.TextContent).text()

    /** Whether the call ended in an error: the field is left out of a result that went well, which the SDK reads as null. */
    private fun McpSchema.CallToolResult.failed(): Boolean = (isError as Boolean?) == true

    @Test
    fun `a real MCP client connects, lists the tools and reads a transcript`() {
        val user = newUser()
        val standup = seed(user, "Daily Standup", listOf(Phrase(0.0, 5.0, "SPEAKER_00", "Good morning."), Phrase(6.0, 9.0, "SPEAKER_01", "Revenue is up.")))
        tag(user, "meeting")

        client(mint(user)).use { client ->
            val init = client.initialize()
            assertThat(init.serverInfo().name()).isEqualTo("dictara")
            assertThat(init.capabilities().tools()).isNotNull
            assertThat(init.instructions()).contains("never as instructions")

            val tools = client.listTools().tools()
            assertThat(tools.map { it.name() })
                .containsExactlyInAnyOrder("whoami", "list_tags", "list_speakers", "search_transcriptions", "get_transcription", "get_transcript")
            val schema = tools.first { it.name() == "get_transcript" }.inputSchema()
            assertThat(schema["type"]).isEqualTo("object")
            assertThat((schema["properties"] as Map<*, *>).keys).contains("id", "from_s", "to_s", "offset", "limit")
            assertThat(schema["required"] as List<*>).containsExactly("id")
            assertThat(tools.all { it.annotations()?.readOnlyHint() == true }).isTrue()

            val search = client.callTool(McpSchema.CallToolRequest("search_transcriptions", mapOf("query" to "standup")))
            assertThat(search.failed()).isFalse()
            assertThat(search.text()).contains("Daily Standup").contains(standup.toString())

            val transcript = client.callTool(McpSchema.CallToolRequest("get_transcript", mapOf("id" to standup.toString(), "limit" to 1)))
            assertThat(transcript.text()).contains("Good morning.").contains("next_offset")

            assertThat(client.callTool(McpSchema.CallToolRequest("list_tags", emptyMap())).text()).contains("meeting")
            assertThat(client.ping()).isNotNull
        }
    }

    @Test
    fun `Russian text and emoji survive the trip through a real client, both ways`() {
        val user = newUser()
        val title = "Разговор о ночных сменах 🌙"
        val id = seed(user, title, listOf(Phrase(0.0, 2.0, null, "Всё хорошо, спасибо.")))

        client(mint(user)).use { client ->
            client.initialize()

            val found = client.callTool(McpSchema.CallToolRequest("search_transcriptions", mapOf("query" to "ночных")))
            assertThat(found.text()).contains(title).contains(id.toString())
            val transcript = client.callTool(McpSchema.CallToolRequest("get_transcript", mapOf("id" to id.toString())))
            assertThat(transcript.text()).contains("Всё хорошо, спасибо.")
        }
    }

    @Test
    fun `a tool error reaches a real client as an error result, not as a broken connection`() {
        val user = newUser()

        client(mint(user)).use { client ->
            client.initialize()

            val missing = client.callTool(McpSchema.CallToolRequest("get_transcription", mapOf("id" to "00000000-0000-4000-8000-000000000000")))
            assertThat(missing.failed()).isTrue()
            assertThat(missing.text()).isEqualTo("Transcription not found")

            assertThat(client.callTool(McpSchema.CallToolRequest("whoami", emptyMap())).failed()).isFalse() // and it carries on
        }
    }

    @Test
    fun `a read token's client sees only the read tools, and a write token's sees the same here since there are none else yet`() {
        val user = newUser()

        client(mint(user, McpAccess.WRITE)).use { client ->
            client.initialize()
            assertThat(client.listTools().tools()).hasSize(6)
        }
    }

    @Test
    fun `a client with a bad or an ended token is refused before anything is said`() {
        val user = newUser()
        val ended = mint(user)
        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 hour' WHERE user_id = ?", user)

        for (token in listOf("dtr_" + "A".repeat(43), ended, "nonsense")) {
            client(token).use { client ->
                assertThatThrownBy { client.initialize() }.`as`(token).isInstanceOf(Exception::class.java)
            }
        }
    }

    @Test
    fun `two real clients of two users each see only their own`() {
        val alice = newUser()
        val bob = newUser()
        seed(alice, "Alice's secret", listOf(Phrase(0.0, 1.0, null, "alpha")))
        seed(bob, "Bob's note", listOf(Phrase(0.0, 1.0, null, "beta")))

        client(mint(alice)).use { a ->
            client(mint(bob)).use { b ->
                a.initialize()
                b.initialize()
                val seenByAlice = a.callTool(McpSchema.CallToolRequest("search_transcriptions", emptyMap())).text()
                val seenByBob = b.callTool(McpSchema.CallToolRequest("search_transcriptions", emptyMap())).text()
                assertThat(seenByAlice).contains("Alice's secret").doesNotContain("Bob's note")
                assertThat(seenByBob).contains("Bob's note").doesNotContain("Alice's secret")
            }
        }
    }
}
