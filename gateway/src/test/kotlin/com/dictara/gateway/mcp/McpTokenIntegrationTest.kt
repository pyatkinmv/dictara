package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.token.McpTokenService
import com.dictara.gateway.mcp.token.TokenCheck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Creating, listing, revoking and checking MCP tokens: `/mcp-tokens` and what is stored. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpTokenIntegrationTest : AbstractMcpIntegrationTest() {

    private fun create(user: UUID, body: String) = session(HttpMethod.POST, "/mcp-tokens", user, body)

    // -- creating -------------------------------------------------------------------------------------------

    @Test
    fun `a token is created with its secret, shown this once, and works for 90 days`() {
        val user = newUser()

        val reply = create(user, """{"name": "OpenCode on laptop", "access": "read"}""")

        assertThat(reply.status).isEqualTo(201)
        val body = reply.body!!
        val secret = body["token"].asText()
        assertThat(secret).startsWith("dtr_").hasSize(47)
        assertThat(body["prefix"].asText()).isEqualTo(secret.take(8))
        assertThat(body["name"].asText()).isEqualTo("OpenCode on laptop")
        assertThat(body["access"].asText()).isEqualTo("read")
        assertThat(body["status"].asText()).isEqualTo("active")
        val expiresAt = Instant.parse(body["expires_at"].asText())
        assertThat(Duration.between(Instant.now(), expiresAt).toDays()).isBetween(89L, 90L)
    }

    @Test
    fun `access is read unless write is asked for, and anything else is refused`() {
        val user = newUser()

        assertThat(create(user, """{"name": "a"}""").body!!["access"].asText()).isEqualTo("read")
        assertThat(create(user, """{"name": "b", "access": "write"}""").body!!["access"].asText()).isEqualTo("write")

        val bad = create(user, """{"name": "c", "access": "admin"}""")
        assertThat(bad.status).isEqualTo(400)
        assertThat(bad.body!!["message"].asText()).contains("Unknown access 'admin'")
    }

    @Test
    fun `a name is needed and is at most 80 characters`() {
        val user = newUser()

        assertThat(create(user, """{}""").status).isEqualTo(400)
        assertThat(create(user, """{"name": "   "}""").status).isEqualTo(400)
        assertThat(create(user, """{"name": "${"x".repeat(81)}"}""").status).isEqualTo(400)
        assertThat(create(user, """{"name": "  ${"x".repeat(80)}  "}""").status).isEqualTo(201) // trimmed first
    }

    @Test
    fun `a user holds at most ten working tokens, and an ended or revoked one does not count`() {
        val user = newUser()
        repeat(10) { assertThat(create(user, """{"name": "t$it"}""").status).isEqualTo(201) }

        val eleventh = create(user, """{"name": "one too many"}""")
        assertThat(eleventh.status).isEqualTo(409)
        assertThat(eleventh.body!!["message"].asText()).contains("10")

        val first = session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"].first()["id"].asText()
        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/$first", user).status).isEqualTo(204)
        assertThat(create(user, """{"name": "now there is room"}""").status).isEqualTo(201)

        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 day' WHERE user_id = ? AND name = 't1'", user)
        assertThat(create(user, """{"name": "and another"}""").status).isEqualTo(201)
    }

    @Test
    fun `only the hash of a token is stored, never the token`() {
        val user = newUser()
        val secret = create(user, """{"name": "hashed"}""").body!!["token"].asText()

        val byHash = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM mcp.access_tokens WHERE token_hash = ?", Int::class.java, McpTokenService.hash(secret),
        )
        val anywhere = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM mcp.access_tokens t WHERE row_to_json(t)::text LIKE ?", Int::class.java, "%$secret%",
        )
        val inAudit = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM mcp.audit_log a WHERE row_to_json(a)::text LIKE ?", Int::class.java, "%$secret%",
        )

        assertThat(byHash).isEqualTo(1)
        assertThat(anywhere).isZero()
        assertThat(inAudit).isZero()
    }

    // -- listing --------------------------------------------------------------------------------------------

    @Test
    fun `the list has no secrets, only the user's own tokens, newest first`() {
        val alice = newUser()
        val bob = newUser()
        val first = create(alice, """{"name": "first"}""").body!!
        create(alice, """{"name": "second", "access": "write"}""")
        create(bob, """{"name": "bob's"}""")

        val list = session(HttpMethod.GET, "/mcp-tokens", alice).body!!["tokens"]

        assertThat(list.map { it["name"].asText() }).containsExactly("second", "first")
        assertThat(list.first().fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "name", "prefix", "access", "status", "created_at", "expires_at") // last_used_at too, once used
            .doesNotContain("token")
        assertThat(list.toString()).doesNotContain(first["token"].asText())
        assertThat(list.last()["prefix"].asText()).isEqualTo(first["prefix"].asText())
    }

    @Test
    fun `a token is active, expiring (within 14 days) or expired, and the list says which`() {
        val user = newUser()
        for (name in listOf("fresh", "soon", "ended")) create(user, """{"name": "$name"}""")
        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() + interval '5 days' WHERE name = 'soon' AND user_id = ?", user)
        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 day' WHERE name = 'ended' AND user_id = ?", user)

        val byName = session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"].associate { it["name"].asText() to it["status"].asText() }

        assertThat(byName).containsEntry("fresh", "active").containsEntry("soon", "expiring").containsEntry("ended", "expired")
    }

    // -- revoking -------------------------------------------------------------------------------------------

    @Test
    fun `a revoked token stops working at once and leaves the list`() {
        val user = newUser()
        val created = create(user, """{"name": "doomed"}""").body!!
        val secret = created["token"].asText()
        assertThat(rpc(secret, "ping").status).isEqualTo(200)

        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/${created["id"].asText()}", user).status).isEqualTo(204)

        val after = rpc(secret, "ping")
        assertThat(after.status).isEqualTo(401)
        assertThat(after.body!!["error"].asText()).isEqualTo("invalid_token")
        assertThat(session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"]).isEmpty()
        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/${created["id"].asText()}", user).status).isEqualTo(404) // already gone
    }

    @Test
    fun `an ended token can be removed from the list`() {
        val user = newUser()
        val id = create(user, """{"name": "ended"}""").body!!["id"].asText()
        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 day' WHERE id = ?::uuid", id)

        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/$id", user).status).isEqualTo(204)
        assertThat(session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"]).isEmpty()
    }

    @Test
    fun `someone else's token cannot be revoked, and a malformed id is a 404`() {
        val alice = newUser()
        val bob = newUser()
        val aliceToken = create(alice, """{"name": "hers"}""").body!!

        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/${aliceToken["id"].asText()}", bob).status).isEqualTo(404)
        assertThat(session(HttpMethod.DELETE, "/mcp-tokens/not-an-id", bob).status).isEqualTo(404)
        assertThat(rpc(aliceToken["token"].asText(), "ping").status).isEqualTo(200) // still hers and working
    }

    // -- who may manage tokens ------------------------------------------------------------------------------

    @Test
    fun `tokens are managed with the apps session only, no session is a 401 and an MCP token is not a session`() {
        val user = newUser()
        val secret = mint(user, McpAccess.WRITE)

        assertThat(http(HttpMethod.GET, "/mcp-tokens", null).status).isEqualTo(401)
        assertThat(http(HttpMethod.GET, "/mcp-tokens", secret).status).isEqualTo(401)
        assertThat(http(HttpMethod.POST, "/mcp-tokens", secret, """{"name": "child"}""").status).isEqualTo(401)
        assertThat(http(HttpMethod.DELETE, "/mcp-tokens/${UUID.randomUUID()}", secret).status).isEqualTo(401)
        assertThat(session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"]).hasSize(1) // nothing was minted
    }

    @Test
    fun `an MCP token is no session for the rest of the API either`() {
        val user = newUser()
        val secret = mint(user, McpAccess.WRITE)

        assertThat(http(HttpMethod.GET, "/transcriptions", secret).status).isEqualTo(401)
        assertThat(http(HttpMethod.GET, "/tags", secret).status).isEqualTo(401)
        assertThat(http(HttpMethod.POST, "/tags", secret, """{"label": "x"}""").status).isEqualTo(401)
    }

    @Test
    fun `the app's session is no MCP token`() {
        val user = newUser()

        val reply = rpc(jwt.issue(user), "ping")

        assertThat(reply.status).isEqualTo(401)
        assertThat(reply.body!!["error"].asText()).isEqualTo("invalid_token")
    }

    // -- checking a token -----------------------------------------------------------------------------------

    @Test
    fun `a token is checked for who it is, ended or not, and only its own format is looked up`() {
        val user = newUser()
        val secret = mint(user, McpAccess.WRITE, "checked")

        val valid = tokens.authenticate(secret) as TokenCheck.Valid
        assertThat(valid.principal.userId).isEqualTo(user)
        assertThat(valid.principal.access).isEqualTo(McpAccess.WRITE)
        assertThat(valid.principal.tokenName).isEqualTo("checked")

        assertThat(tokens.authenticate("dtr_" + "A".repeat(43))).isEqualTo(TokenCheck.Invalid) // right shape, not issued
        assertThat(tokens.authenticate("")).isEqualTo(TokenCheck.Invalid)
        assertThat(tokens.authenticate("nope")).isEqualTo(TokenCheck.Invalid)
        assertThat(tokens.authenticate(secret.drop(1))).isEqualTo(TokenCheck.Invalid)
        assertThat(tokens.authenticate(secret.replaceFirst("dtr_", "xxx_"))).isEqualTo(TokenCheck.Invalid)

        jdbcTemplate.update("UPDATE mcp.access_tokens SET expires_at = now() - interval '1 second' WHERE token_hash = ?", McpTokenService.hash(secret))
        assertThat(tokens.authenticate(secret)).isEqualTo(TokenCheck.Expired)
    }

    @Test
    fun `using a token notes when it was last used`() {
        val user = newUser()
        val secret = mint(user)
        assertThat(session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"].first()["last_used_at"]).isNull()

        rpc(secret, "ping")

        assertThat(session(HttpMethod.GET, "/mcp-tokens", user).body!!["tokens"].first()["last_used_at"]).isNotNull()
    }

    @Test
    fun `removing a user removes their tokens`() {
        val user = newUser()
        val secret = mint(user)

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user)

        assertThat(tokens.authenticate(secret)).isEqualTo(TokenCheck.Invalid)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mcp.access_tokens WHERE user_id = ?", Int::class.java, user)).isZero()
    }

    @Test
    fun `the mcp schema has its own migration history`() {
        val applied = jdbcTemplate.queryForList("SELECT version FROM mcp.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String::class.java)
        val main = jdbcTemplate.queryForObject("SELECT count(*) FROM public.flyway_schema_history", Int::class.java)

        assertThat(applied).containsExactly("1")
        assertThat(main).isGreaterThan(30) // the main history is a separate one
    }
}
