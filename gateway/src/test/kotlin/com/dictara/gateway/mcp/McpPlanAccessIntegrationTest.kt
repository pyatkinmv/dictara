package com.dictara.gateway.mcp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.http.HttpMethod
import java.util.UUID

/**
 * MCP access is a property of the plan (`mcp_access` in `plans.constraints`), not a check of the plan's name: who can
 * make tokens, and whether the tokens they have keep working.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpPlanAccessIntegrationTest : AbstractMcpIntegrationTest() {

    private fun create(user: UUID) = session(HttpMethod.POST, "/mcp-tokens", user, """{"name": "agent"}""")

    private fun list(user: UUID) = session(HttpMethod.GET, "/mcp-tokens", user)

    private fun planOf(user: UUID, plan: String) {
        jdbcTemplate.update("UPDATE users SET plan = ? WHERE id = ?", plan, user)
    }

    private fun planWith(name: String, constraints: String) {
        jdbcTemplate.update(
            "INSERT INTO plans(name, display_name, constraints) VALUES (?, ?, ?::jsonb) ON CONFLICT (name) DO UPDATE SET constraints = EXCLUDED.constraints",
            name, name, constraints,
        )
    }

    // -- which plans have it ---------------------------------------------------------------------------------

    @Test
    fun `only Ultra includes MCP access among the plans that exist`() {
        val granted = jdbcTemplate.queryForList("SELECT name FROM plans WHERE (constraints ->> 'mcp_access') = 'true' ORDER BY name", String::class.java)

        assertThat(granted).contains("unlimited").doesNotContain("free", "anonymous")
    }

    // -- making tokens ---------------------------------------------------------------------------------------

    @Test
    fun `a user on Ultra makes tokens, and the list says MCP is available`() {
        val user = newUser("unlimited")

        assertThat(list(user).body!!["mcp_available"].asBoolean()).isTrue()
        assertThat(create(user).status).isEqualTo(201)
    }

    @Test
    fun `a user on Free and a guest cannot make tokens, with a reason, and the list says MCP is not available`() {
        for (plan in listOf("free", "anonymous")) {
            val user = newUser(plan)

            val refused = create(user)

            assertThat(refused.status).`as`(plan).isEqualTo(403)
            assertThat(refused.body!!["message"].asText()).contains("not included in your plan")
            assertThat(list(user).body!!["mcp_available"].asBoolean()).`as`(plan).isFalse()
            assertThat(list(user).body!!["tokens"]).hasSize(0)
        }
    }

    @Test
    fun `it is the constraint that decides and not the name of the plan`() {
        planWith("mcp_granted", """{"monthly_submissions": 50, "mcp_access": true}""")
        planWith("mcp_denied", """{"mcp_access": false}""")
        planWith("mcp_silent", """{"monthly_submissions": 50}""")

        assertThat(create(newUser("mcp_granted")).status).isEqualTo(201)
        assertThat(create(newUser("mcp_denied")).status).isEqualTo(403)
        assertThat(create(newUser("mcp_silent")).status).isEqualTo(403) // no key: not included, unlike a limit
    }

    // -- tokens already made ---------------------------------------------------------------------------------

    @Test
    fun `tokens stop working when the plan stops including MCP, at once, and work again when it does`() {
        val user = newUser("unlimited")
        val token = mint(user)
        assertThat(rpc(token, "tools/list").status).isEqualTo(200)

        planOf(user, "free")

        val refused = rpc(token, "tools/list")
        assertThat(refused.status).isEqualTo(403)
        assertThat(refused.body!!["error"].asText()).isEqualTo("mcp_not_in_plan")
        assertThat(rpc(token, "tools/call", """{"name":"list_tags","arguments":{}}""").status).isEqualTo(403)

        planOf(user, "unlimited")

        assertThat(rpc(token, "tools/list").status).isEqualTo(200) // the token was kept, not revoked
    }

    @Test
    fun `after a downgrade the tokens are still listed and can be revoked, but no new one can be made`() {
        val user = newUser("unlimited")
        val id = create(user).body!!["id"].asText()
        planOf(user, "free")

        val listed = list(user).body!!
        assertThat(listed["tokens"]).hasSize(1)
        assertThat(listed["mcp_available"].asBoolean()).isFalse()
        assertThat(create(user).status).isEqualTo(403)
        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/$id", user).status).isEqualTo(204)
    }

    @Test
    fun `a token whose plan does not include MCP never reaches the tools, so nothing is audited for it`() {
        val user = newUser("unlimited")
        val token = mint(user)
        planOf(user, "anonymous")

        assertThat(call403(token)).isEqualTo(403)
        assertThat(auditRows(token)).isEmpty()
    }

    private fun call403(token: String) = rpc(token, "tools/call", """{"name":"whoami","arguments":{}}""").status
}
