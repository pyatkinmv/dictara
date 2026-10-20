package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.protocol.McpRateLimiter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/** The limits on one token: calls a minute, and how much of a long transcript one call returns. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = ["dictara.mcp.rate-limit-per-minute=5", "dictara.mcp.max-result-chars=400"])
class McpLimitsIntegrationTest : AbstractMcpIntegrationTest() {

    @Test
    fun `a token that calls too often is answered with 429 and a wait, and another token is not held up`() {
        val busy = mint(newUser(), name = "busy")
        val calm = mint(newUser(), name = "calm")

        repeat(5) { assertThat(rpc(busy, "ping").status).isEqualTo(200) }

        val refused = rpc(busy, "ping")
        assertThat(refused.status).isEqualTo(429)
        assertThat(refused.headers.getFirst("Retry-After")).isEqualTo("60")
        assertThat(refused.body!!["error"]["message"].asText()).contains("at most 5 a minute")
        assertThat(rpc(calm, "ping").status).isEqualTo(200)
    }

    @Test
    fun `a result longer than the limit is cut, with a note on how to ask for the rest`() {
        val user = newUser()
        val long = (0 until 40).map { Phrase(it * 5.0, it * 5.0 + 4.0, "SPEAKER_00", "A fairly long phrase number $it of this recording.") }
        val id = seed(user, "Long one", long)
        val token = mint(user)

        val reply = call(token, "get_transcript", """{"id":"$id","limit":40}""")

        assertThat(reply.isError).isFalse()
        assertThat(reply.text).contains("[Cut here")
        assertThat(reply.text.length).isLessThan(800)
        assertThat(reply.text).contains("'limit'").contains("'offset'")
    }

    @Test
    fun `a small result is not cut`() {
        val user = newUser()
        val id = seed(user, "Short", listOf(Phrase(0.0, 1.0, null, "Hi.")))

        val reply = call(mint(user), "get_transcript", """{"id":"$id","limit":10}""")

        assertThat(reply.text).doesNotContain("[Cut here")
        assertThat(reply.data["phrases"]).hasSize(1)
    }

    @Test
    fun `the limiter counts in calendar minutes per token`() {
        val limiter = McpRateLimiter(McpProperties(rateLimitPerMinute = 3))
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val minute = 1_000 * 60_000L

        assertThat((1..3).map { limiter.tryAcquire(a, minute + it) }).containsOnly(true)
        assertThat(limiter.tryAcquire(a, minute + 10)).isFalse()
        assertThat(limiter.tryAcquire(b, minute + 10)).isTrue() // another token has its own count
        assertThat(limiter.tryAcquire(a, minute + 60_000)).isTrue() // the next minute starts afresh
    }
}
