package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.tools.McpTool
import com.dictara.gateway.mcp.tools.ToolContext
import com.dictara.gateway.mcp.tools.ToolRegistry
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The checks the tool registry makes at startup, and the access rules, without a database or Spring. */
class McpRegistryTest {

    private fun tool(
        name: String = "some_tool",
        description: String = "Does something.",
        access: McpAccess = McpAccess.READ,
        schema: Map<String, Any?> = mapOf("type" to "object"),
    ) = object : McpTool {
        override val name = name
        override val description = description
        override val access = access
        override val inputSchema = schema
        override fun call(context: ToolContext, arguments: JsonNode): Any = "ok"
    }

    @Test
    fun `tools are found by name, listed in name order`() {
        val registry = ToolRegistry(listOf(tool("zeta"), tool("alpha"), tool("mid_1")))

        assertThat(registry.all.map { it.name }).containsExactly("alpha", "mid_1", "zeta")
        assertThat(registry.find("mid_1")?.name).isEqualTo("mid_1")
        assertThat(registry.find("nope")).isNull()
        assertThat(registry.find("ALPHA")).isNull() // names are exact
    }

    @Test
    fun `two tools with one name stop the application from starting`() {
        assertThatThrownBy { ToolRegistry(listOf(tool("same"), tool("same"))) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("share a name").hasMessageContaining("same")
    }

    @Test
    fun `a name that is not lower_snake_case, a missing description or arguments that are not an object are refused at startup`() {
        for (bad in listOf("Upper", "with-dash", "with space", "1starts_with_digit", "", "a".repeat(65), "ünï")) {
            assertThatThrownBy { ToolRegistry(listOf(tool(name = bad))) }.`as`(bad).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { ToolRegistry(listOf(tool(description = "   "))) }.hasMessageContaining("needs a description")
        assertThatThrownBy { ToolRegistry(listOf(tool(schema = mapOf("type" to "string")))) }.hasMessageContaining("must take an object")
        assertThatThrownBy { ToolRegistry(listOf(tool(schema = emptyMap()))) }.hasMessageContaining("must take an object")
        assertThat(ToolRegistry(listOf(tool(name = "a".repeat(64)))).all).hasSize(1) // 64 is the longest allowed
    }

    @Test
    fun `a token is offered the tools its access allows`() {
        val registry = ToolRegistry(listOf(tool("reader", access = McpAccess.READ), tool("writer", access = McpAccess.WRITE)))

        assertThat(registry.availableTo(McpAccess.READ).map { it.name }).containsExactly("reader")
        assertThat(registry.availableTo(McpAccess.WRITE).map { it.name }).containsExactly("reader", "writer")
    }

    @Test
    fun `access is told by its code, read being the lowest and nothing being guessed`() {
        assertThat(McpAccess.parse("read")).isEqualTo(McpAccess.READ)
        assertThat(McpAccess.parse("write")).isEqualTo(McpAccess.WRITE)
        for (other in listOf("READ", "admin", "", " read", null)) assertThat(McpAccess.parse(other)).`as`(other).isNull()
        assertThat(McpAccess.entries.map { it.param }).containsExactly("read", "write")
    }

    @Test
    fun `the real tools are all read tools for now, each with a clear name`() {
        // The write tools come with the next stage; until then nothing may be able to change anything.
        val real = ToolRegistry(
            listOf(
                com.dictara.gateway.mcp.tools.WhoAmITool(stubFacade()), com.dictara.gateway.mcp.tools.ListTagsTool(stubFacade()),
                com.dictara.gateway.mcp.tools.ListSpeakersTool(stubFacade()), com.dictara.gateway.mcp.tools.SearchTranscriptionsTool(stubFacade()),
                com.dictara.gateway.mcp.tools.GetTranscriptionTool(stubFacade()), com.dictara.gateway.mcp.tools.GetTranscriptTool(stubFacade()),
            ),
        )

        assertThat(real.all.map { it.access }.toSet()).containsExactly(McpAccess.READ)
        assertThat(real.all.map { it.name }).containsExactly(
            "get_transcript", "get_transcription", "list_speakers", "list_tags", "search_transcriptions", "whoami",
        )
    }

    private fun stubFacade() = org.mockito.Mockito.mock(com.dictara.gateway.mcp.facade.McpReadFacade::class.java)
}
