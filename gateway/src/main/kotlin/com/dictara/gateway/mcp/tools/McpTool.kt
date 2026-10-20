package com.dictara.gateway.mcp.tools

import com.dictara.gateway.mcp.token.McpAccess
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import java.util.UUID

/** Who a tool is being run for. The user is the owner of the token; a tool never takes a user id from its arguments. */
data class ToolContext(val userId: UUID, val tokenName: String, val access: McpAccess)

/**
 * One thing an agent can do. A tool declares the [access] a token needs for it, and nothing more: there is no such
 * thing as a tool that spends money (see `guard/PaidOperation.kt`). A tool is a Spring bean in `mcp.tools`, may call
 * only the classes of `mcp.facade`, and returns something that turns into JSON.
 */
interface McpTool {
    val name: String
    val description: String

    /** The right a token must have to use the tool: [McpAccess.READ] for tools that only look, [McpAccess.WRITE] for tools that change. */
    val access: McpAccess

    /** JSON Schema of the arguments (an `object`). */
    val inputSchema: Map<String, Any?>

    fun call(context: ToolContext, arguments: JsonNode): Any
}

/** All the tools, checked when the application starts (names, descriptions), and found by name. */
@Component
class ToolRegistry(tools: List<McpTool>) {
    val all: List<McpTool> = tools.sortedBy { it.name }
    private val byName = all.associateBy { it.name }

    init {
        require(byName.size == all.size) { "Two MCP tools share a name: ${all.groupBy { it.name }.filter { it.value.size > 1 }.keys}" }
        all.forEach {
            require(NAME.matches(it.name)) { "MCP tool name '${it.name}' must be lower_snake_case" }
            require(it.description.isNotBlank()) { "MCP tool '${it.name}' needs a description" }
            require(it.inputSchema["type"] == "object") { "MCP tool '${it.name}' must take an object" }
        }
    }

    fun find(name: String): McpTool? = byName[name]

    /** The tools a token with this access can use. */
    fun availableTo(access: McpAccess): List<McpTool> = all.filter { access.allows(it.access) }

    companion object {
        private val NAME = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

/** Small helpers to write the JSON Schema of a tool in a line each. */
internal object Schema {
    fun obj(vararg properties: Pair<String, Map<String, Any?>>, required: List<String> = emptyList()): Map<String, Any?> = buildMap {
        put("type", "object")
        put("properties", linkedMapOf(*properties))
        if (required.isNotEmpty()) put("required", required)
        put("additionalProperties", false)
    }

    fun string(description: String, enum: List<String>? = null): Map<String, Any?> =
        buildMap { put("type", "string"); put("description", description); enum?.let { put("enum", it) } }

    fun integer(description: String, min: Int? = null, max: Int? = null): Map<String, Any?> =
        buildMap { put("type", "integer"); put("description", description); min?.let { put("minimum", it) }; max?.let { put("maximum", it) } }

    fun number(description: String): Map<String, Any?> = mapOf("type" to "number", "description" to description)

    fun strings(description: String): Map<String, Any?> =
        mapOf("type" to "array", "description" to description, "items" to mapOf("type" to "string"))
}
