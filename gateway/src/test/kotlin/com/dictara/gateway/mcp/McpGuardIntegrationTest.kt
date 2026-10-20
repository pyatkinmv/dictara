package com.dictara.gateway.mcp

import com.dictara.gateway.guard.CallOrigin
import com.dictara.gateway.guard.PaidOperation
import com.dictara.gateway.guard.PaidOperationNotAllowedException
import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.tools.McpTool
import com.dictara.gateway.mcp.tools.ToolContext
import com.dictara.gateway.mcp.tools.ToolRegistry
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.util.concurrent.atomic.AtomicInteger

/** Something that costs money, as the real adapters do: it carries the mark. Open, so that Spring can proxy it. */
open class PaidProbe {
    private val spent = AtomicInteger()

    @PaidOperation
    open fun spend(): String {
        spent.incrementAndGet()
        return "spent"
    }

    // Plain methods rather than a property: the bean is a proxy, which would answer a property from its own empty field.
    open fun times(): Int = spent.get()
    open fun reset() = spent.set(0)
}

/** A tool that, by mistake, reaches paid work. It must never get to spend anything. */
class SpendingTool(private val probe: PaidProbe) : McpTool {
    override val name = "probe_spend"
    override val description = "Test only: reaches a paid operation."
    override val access = McpAccess.READ
    override val inputSchema = mapOf<String, Any?>("type" to "object", "properties" to emptyMap<String, Any?>())
    override fun call(context: ToolContext, arguments: JsonNode): Any = probe.spend()
}

/** A harmless tool that needs write access. */
class WritingTool : McpTool {
    override val name = "probe_write"
    override val description = "Test only: needs a write token."
    override val access = McpAccess.WRITE
    override val inputSchema = mapOf<String, Any?>("type" to "object", "properties" to emptyMap<String, Any?>())
    override fun call(context: ToolContext, arguments: JsonNode): Any = mapOf("done" to true)
}

/** Not scanned (it is a test configuration): only the tests that import it get the probe tools. */
@TestConfiguration
class McpProbeConfiguration {
    @Bean fun paidProbe() = PaidProbe()
    @Bean fun spendingTool(probe: PaidProbe) = SpendingTool(probe)
    @Bean fun writingTool() = WritingTool()
}

/**
 * The guard against paid work, and the rights of a token, end to end: with probe tools added to the tools of the app.
 * A tool that reaches something marked [PaidOperation] is refused whatever the token is, and nothing is spent.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(McpProbeConfiguration::class)
class McpGuardIntegrationTest : AbstractMcpIntegrationTest() {

    @Autowired lateinit var probe: PaidProbe
    @Autowired lateinit var registry: ToolRegistry

    @BeforeEach
    fun reset() = probe.reset()

    @Test
    fun `a tool that reaches paid work is refused, with any token, and nothing is spent`() {
        val user = newUser()
        for (access in McpAccess.entries) {
            val token = mint(user, access, "token $access")

            val reply = call(token, "probe_spend")

            assertThat(reply.isError).`as`(access.name).isTrue()
            assertThat(reply.text).contains("costs money").contains("not available through MCP")
            assertThat(auditRows(token).last()["outcome"]).isEqualTo("denied")
        }
        assertThat(probe.times()).isZero()
    }

    @Test
    fun `the same paid work is allowed to everyone else, the app, the bot and the jobs`() {
        assertThat(CallOrigin.current()).isEqualTo(CallOrigin.WEB)

        assertThat(probe.spend()).isEqualTo("spent")

        assertThat(probe.times()).isEqualTo(1)
    }

    @Test
    fun `paid work is refused only while the call runs as MCP, on that thread, and the origin is put back`() {
        assertThatThrownBy { CallOrigin.runAs(CallOrigin.MCP) { probe.spend() } }
            .isInstanceOf(PaidOperationNotAllowedException::class.java)
            .hasMessageContaining("costs money")
        assertThat(CallOrigin.current()).isEqualTo(CallOrigin.WEB) // restored, even after the exception
        assertThat(probe.times()).isZero()

        CallOrigin.runAs(CallOrigin.MCP) {
            assertThat(CallOrigin.current()).isEqualTo(CallOrigin.MCP)
            CallOrigin.runAs(CallOrigin.WEB) { assertThat(CallOrigin.current()).isEqualTo(CallOrigin.WEB) }
            assertThat(CallOrigin.current()).isEqualTo(CallOrigin.MCP)
        }
        assertThat(CallOrigin.current()).isEqualTo(CallOrigin.WEB)

        var seenOnAnotherThread: CallOrigin? = null
        CallOrigin.runAs(CallOrigin.MCP) { Thread { seenOnAnotherThread = CallOrigin.current() }.also { it.start() }.join() }
        assertThat(seenOnAnotherThread).isEqualTo(CallOrigin.WEB) // the mark does not travel: see PaidOperation's note
    }

    @Test
    fun `a read token is not offered a write tool, cannot call it, and the refusal is written down`() {
        val user = newUser()
        val read = mint(user, McpAccess.READ, "reader")
        val write = mint(user, McpAccess.WRITE, "writer")

        fun offered(token: String) = rpc(token, "tools/list").body!!["result"]["tools"].map { it["name"].asText() }
        assertThat(offered(read)).doesNotContain("probe_write").contains("list_tags")
        assertThat(offered(write)).contains("probe_write", "list_tags")
        val writing = rpc(write, "tools/list").body!!["result"]["tools"].first { it["name"].asText() == "probe_write" }
        assertThat(writing["annotations"]["readOnlyHint"].asBoolean()).isFalse()

        val refused = call(read, "probe_write")
        assertThat(refused.isError).isTrue()
        assertThat(refused.text).contains("read-only")
        assertThat(auditRows(read).last()["outcome"]).isEqualTo("denied")

        val done = call(write, "probe_write")
        assertThat(done.isError).isFalse()
        assertThat(done.data["done"].asBoolean()).isTrue()
        assertThat(auditRows(write).last()["outcome"]).isEqualTo("ok")
    }

    @Test
    fun `write includes read, and read does not include write`() {
        assertThat(McpAccess.WRITE.allows(McpAccess.READ)).isTrue()
        assertThat(McpAccess.WRITE.allows(McpAccess.WRITE)).isTrue()
        assertThat(McpAccess.READ.allows(McpAccess.READ)).isTrue()
        assertThat(McpAccess.READ.allows(McpAccess.WRITE)).isFalse()
    }

    @Test
    fun `every tool declares what it needs, a name, a description and an object for arguments`() {
        assertThat(registry.all).isNotEmpty()
        registry.all.forEach {
            assertThat(it.name).matches("[a-z][a-z0-9_]*")
            assertThat(it.description).isNotBlank()
            assertThat(it.access).isIn(McpAccess.READ, McpAccess.WRITE)
            assertThat(it.inputSchema["type"]).isEqualTo("object")
        }
    }
}
