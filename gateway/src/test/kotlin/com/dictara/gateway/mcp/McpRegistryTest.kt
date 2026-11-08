package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.facade.McpReadFacade
import com.dictara.gateway.mcp.facade.McpWriteFacade
import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.tools.*
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
    fun `the real tools are the read tools and the write tools, each with a clear name and the right access`() {
        val read = stubReadFacade()
        val write = stubWriteFacade()
        val real = ToolRegistry(
            listOf(
                WhoAmITool(read), ListTagsTool(read), ListSpeakersTool(read), SearchTranscriptionsTool(read),
                GetTranscriptionTool(read), GetTranscriptTool(read),
                CreateTagTool(write), UpdateTagTool(write), DeleteTagTool(write),
                CreateSpeakerTool(write), UpdateSpeakerTool(write), DeleteSpeakerTool(write),
                AddTagToTranscriptionTool(write), RemoveTagFromTranscriptionTool(write),
                RenameTranscriptionTool(write), SetSummaryTool(write), EditPhrasesTool(write),
                SetVoiceSpeakerTool(write), SetPhraseSpeakerTool(write),
                AcceptSuggestionTool(write), RejectSuggestionTool(write), AcceptAllSuggestionsTool(write), DismissAllSuggestionsTool(write),
            ),
        )

        assertThat(real.availableTo(McpAccess.READ).map { it.name }).containsExactly(
            "get_transcript", "get_transcription", "list_speakers", "list_tags", "search_transcriptions", "whoami",
        )
        assertThat(real.all.filter { it.access == McpAccess.WRITE }.map { it.name }).containsExactly(
            "accept_all_suggestions", "accept_suggestion", "add_tag_to_transcription", "create_speaker", "create_tag",
            "delete_speaker", "delete_tag", "dismiss_all_suggestions", "edit_phrases", "reject_suggestion",
            "remove_tag_from_transcription", "rename_transcription", "set_phrase_speaker", "set_summary", "set_voice_speaker",
            "update_speaker", "update_tag",
        )
        // Only the tools that remove something for good say so.
        assertThat(real.all.filter { it.destructive }.map { it.name }).containsExactly("delete_speaker", "delete_tag")
        // Every tool that takes a transcription asks for its id, and every required argument is one it declares.
        real.all.forEach { tool ->
            val properties: Set<String> = (tool.inputSchema["properties"] as? Map<*, *>)?.keys?.map { it.toString() }?.toSet() ?: emptySet()
            @Suppress("UNCHECKED_CAST")
            val required = tool.inputSchema["required"] as? List<String> ?: emptyList()
            assertThat(properties.containsAll(required)).`as`("${tool.name} requires only what it declares").isTrue()
        }
    }

    private fun stubReadFacade() = org.mockito.Mockito.mock(McpReadFacade::class.java)
    private fun stubWriteFacade() = org.mockito.Mockito.mock(McpWriteFacade::class.java)
}
