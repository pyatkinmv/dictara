package com.dictara.gateway.mcp.audit

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

enum class CallOutcome(val param: String) {
    /** The tool ran. */
    OK("ok"),

    /** Refused: the token lacks the access the tool needs, or the tool reached something paid. */
    DENIED("denied"),

    /** The tool failed (a bad argument, not found, a bug). */
    ERROR("error"),
}

/**
 * Who called which tool and how it ended (`mcp.audit_log`): the token, the tool's name, the outcome and the time it took.
 * Never the arguments or what came back, which can hold what people said in their recordings.
 */
@Component
class McpAuditLog(private val jdbc: NamedParameterJdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A failure to write the record never fails the call itself. */
    fun record(tokenId: UUID, userId: UUID, tool: String, outcome: CallOutcome, durationMs: Long) {
        try {
            jdbc.update(
                "INSERT INTO mcp.audit_log (token_id, user_id, tool, outcome, duration_ms) VALUES (:token, :user, :tool, :outcome, :ms)",
                mapOf("token" to tokenId, "user" to userId, "tool" to tool.take(64), "outcome" to outcome.param, "ms" to durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
            )
        } catch (e: Exception) {
            log.warn("MCP audit record not written: {}", e.message)
        }
    }
}
