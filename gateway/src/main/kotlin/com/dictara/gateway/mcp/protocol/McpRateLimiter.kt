package com.dictara.gateway.mcp.protocol

import com.dictara.gateway.mcp.McpProperties
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * At most `dictara.mcp.rate-limit-per-minute` tool calls a minute for one token, counted in the calendar minute: an agent
 * stuck in a loop cannot hammer the database. In memory, which is enough: the limit is per token and the gateway is one process.
 */
@Component
class McpRateLimiter(private val props: McpProperties) {
    private class Window(val minute: Long) {
        var count = 0
    }

    private val windows = ConcurrentHashMap<UUID, Window>()

    /** True when the call may go on. [now] is for tests. */
    fun tryAcquire(tokenId: UUID, now: Long = System.currentTimeMillis()): Boolean {
        val minute = now / 60_000
        val window = windows.compute(tokenId) { _, w -> if (w == null || w.minute != minute) Window(minute) else w }!!
        synchronized(window) {
            if (window.count >= props.rateLimitPerMinute) return false
            window.count++
            return true
        }
    }
}
