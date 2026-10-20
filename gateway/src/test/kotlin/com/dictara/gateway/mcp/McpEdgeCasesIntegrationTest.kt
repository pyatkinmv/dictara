package com.dictara.gateway.mcp

import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.token.McpTokenService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** What a careless or hostile client can send: odd shapes, huge bodies, injection, many at once. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpEdgeCasesIntegrationTest : AbstractMcpIntegrationTest() {

    @Autowired lateinit var migrator: McpSchemaMigrator

    private fun raw(token: String?, body: String, contentType: MediaType = MediaType.APPLICATION_JSON, extra: HttpHeaders.() -> Unit = {}): Reply {
        val headers = HttpHeaders().apply {
            token?.let { set("Authorization", it) }
            this.contentType = contentType
            extra()
        }
        val response = rest.exchange("/mcp", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        val text = response.body?.takeIf { it.isNotBlank() }
        return Reply(response.statusCode.value(), text?.let { runCatching { json.readTree(it) }.getOrNull() }, response.headers)
    }

    // -- the shape of the request ---------------------------------------------------------------------------

    @Test
    fun `the id can be a number or a string and is answered as it came`() {
        val token = mint(newUser())

        assertThat(raw("Bearer $token", """{"jsonrpc":"2.0","id":"abc-1","method":"ping"}""").body!!["id"].asText()).isEqualTo("abc-1")
        assertThat(raw("Bearer $token", """{"jsonrpc":"2.0","id":42,"method":"ping"}""").body!!["id"].asInt()).isEqualTo(42)
    }

    @Test
    fun `a request with no params, odd params or odd arguments is answered, not crashed on`() {
        val token = mint(newUser())
        fun send(body: String) = raw("Bearer $token", body)

        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""").body!!["result"]["serverInfo"]["name"].asText()).isEqualTo("dictara")
        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":[1,2]}""").status).isEqualTo(200)
        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_tags","arguments":[1]}}""").body!!["result"]["content"][0]["text"].asText())
            .isEqualTo("[]") // arguments that are not an object count as none
        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":5}}""").body!!["error"]["code"].asInt()).isEqualTo(-32602)
        assertThat(send("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":"nope"}""").body!!["error"]["code"].asInt()).isEqualTo(-32602)
        assertThat(send("""{"jsonrpc":"2.0","id":null,"method":"ping"}""").status).isEqualTo(202) // a null id is a notification
        assertThat(send("null").status).isEqualTo(400)
        assertThat(send("42").status).isEqualTo(400)
        assertThat(send("").status).isEqualTo(400)
    }

    @Test
    fun `only JSON is accepted`() {
        val token = mint(newUser())

        assertThat(raw("Bearer $token", "ping", MediaType.TEXT_PLAIN).status).isEqualTo(415)
        assertThat(raw("Bearer $token", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", MediaType.parseMediaType("application/json; charset=utf-8")).status).isEqualTo(200)
    }

    // -- text that is not English ---------------------------------------------------------------------------

    @Test
    fun `Russian and other text goes out as UTF-8, and says so, so that a client that guesses does not garble it`() {
        val user = newUser()
        val title = "Созвон с Арсеном: Харбин, ИИ для математики 🎓"
        val id = seed(user, title, listOf(Phrase(0.0, 3.0, "SPEAKER_00", "Привет, как дела? Это проверка.")))
        tag(user, "встреча", "Регулярные встречи")
        speaker(user, "Мария Чен")
        val token = mint(user)

        val search = http(HttpMethod.POST, "/mcp", token, """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"search_transcriptions","arguments":{"query":"Харбин"}}}""")

        assertThat(search.headers.contentType.toString()).contains("charset=UTF-8")
        val items = json.readTree(search.body!!["result"]["content"][0]["text"].asText())["items"]
        assertThat(items.map { it["title"].asText() }).containsExactly(title) // found by Cyrillic text, and back unharmed
        val phrases = call(token, "get_transcript", """{"id":"$id"}""").data["phrases"]
        assertThat(phrases[0]["text"].asText()).isEqualTo("Привет, как дела? Это проверка.")
        assertThat(call(token, "list_tags").data[0]["label"].asText()).isEqualTo("встреча")
        assertThat(call(token, "list_speakers").data[0]["name"].asText()).isEqualTo("Мария Чен")
        assertThat(rpc("dtr_" + "A".repeat(43), "ping").headers.contentType.toString()).contains("charset=UTF-8") // refusals too
    }

    // -- the token in the header ----------------------------------------------------------------------------

    @Test
    fun `the Bearer scheme is read as any case, and nothing else is a token`() {
        val token = mint(newUser())
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        assertThat(raw("bearer $token", ping).status).isEqualTo(200)
        assertThat(raw("BEARER $token", ping).status).isEqualTo(200)
        assertThat(raw("Bearer  $token ", ping).status).isEqualTo(200) // stray spaces
        assertThat(raw("Basic $token", ping).status).isEqualTo(401)
        assertThat(raw(token, ping).status).isEqualTo(401) // no scheme
        assertThat(raw("Bearer ${token}x", ping).status).isEqualTo(401)
        assertThat(raw("Bearer ${token.dropLast(1)}", ping).status).isEqualTo(401)
        assertThat(raw("Bearer ${token.lowercase()}", ping).status).isEqualTo(401) // tokens are case sensitive
    }

    @Test
    fun `a token in the address or in a cookie is not read`() {
        val token = mint(newUser())
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON; add("Cookie", "token=$token") }

        assertThat(rest.exchange("/mcp?token=$token&access_token=$token", HttpMethod.POST, HttpEntity(body, headers), String::class.java).statusCode.value()).isEqualTo(401)
    }

    // -- size, speed and many at once -----------------------------------------------------------------------

    @Test
    fun `a body over the limit is refused with 413, one under it is read`() {
        val token = mint(newUser())
        val padding = "x".repeat(300_000)

        val big = raw("Bearer $token", """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"$padding"}}""")
        assertThat(big.status).isEqualTo(413)
        assertThat(big.body!!["error"]["message"].asText()).contains("larger than")

        val fine = raw("Bearer $token", """{"jsonrpc":"2.0","id":1,"method":"ping","params":{"pad":"${"x".repeat(100_000)}"}}""")
        assertThat(fine.status).isEqualTo(200)
    }

    @Test
    fun `a big body with no token is not read at all`() {
        val reply = raw(null, """{"pad":"${"x".repeat(300_000)}"}""")

        assertThat(reply.status).isEqualTo(401)
    }

    @Test
    fun `many calls at once with one token all get their answer, each its own`() {
        val user = newUser()
        val token = mint(user)
        seed(user, "Parallel", listOf(Phrase(0.0, 1.0, null, "x")))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..40).map { n ->
                Callable { http(HttpMethod.POST, "/mcp", token, """{"jsonrpc":"2.0","id":$n,"method":"tools/call","params":{"name":"search_transcriptions","arguments":{"query":"Parallel"}}}""") }
            }).map { it.get() }

            assertThat(results.map { it.status }).containsOnly(200)
            assertThat(results.map { it.body!!["id"].asInt() }).containsExactlyInAnyOrderElementsOf((1..40).toList()) // each answer is for its own request
            assertThat(results.all { it.body!!["result"]["content"][0]["text"].asText().contains("Parallel") }).isTrue()
        } finally {
            pool.shutdownNow()
        }
    }

    // -- injection and odd text -----------------------------------------------------------------------------

    @Test
    fun `text meant to break a query is just text`() {
        val user = newUser()
        seed(user, "100% sure_thing", listOf(Phrase(0.0, 1.0, null, "x")))
        seed(user, "Another", listOf(Phrase(0.0, 1.0, null, "y")))
        val token = mint(user)

        for (nasty in listOf("'; DROP TABLE users; --", "\" OR 1=1 --", "%", "_", "\\", "' OR '1'='1", "<script>alert(1)</script>")) {
            val reply = call(token, "search_transcriptions", """{"query":${json.writeValueAsString(nasty)}}""")
            assertThat(reply.isError).`as`(nasty).isFalse()
        }
        // A NUL character cannot be stored in text: it is refused in plain words, in the query and in the lists alike.
        for (args in listOf("{\"query\":\"a\\u0000b\"}", "{\"tags\":[\"a\\u0000\"]}", "{\"speakers\":[\"\\u0000\"]}")) {
            val reply = call(token, "search_transcriptions", args)
            assertThat(reply.isError).`as`(args).isTrue()
            assertThat(reply.text).contains("not allowed")
        }
        // The wildcards of LIKE mean nothing: '%' finds only a title that has a percent sign.
        assertThat(call(token, "search_transcriptions", """{"query":"%"}""").data["items"].map { it["title"].asText() }).containsExactly("100% sure_thing")
        assertThat(call(token, "search_transcriptions", """{"query":"_"}""").data["items"].map { it["title"].asText() }).containsExactly("100% sure_thing")
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Int::class.java)).isPositive() // still there
    }

    @Test
    fun `a name with any text, emoji and quotes included, is kept as it is`() {
        val user = newUser()
        val name = "Ноутбук 💻 \"work\" 'x'; DROP TABLE mcp.access_tokens;--"

        val created = tokens.create(user, name, McpAccess.READ)

        assertThat(created.token.name).isEqualTo(name)
        assertThat(tokens.list(user).single().name).isEqualTo(name)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mcp.access_tokens", Int::class.java)).isPositive()
    }

    // -- upkeep ---------------------------------------------------------------------------------------------

    @Test
    fun `using a token twice within a minute notes the first time only`() {
        val user = newUser()
        val token = mint(user)
        fun lastUsed() = jdbcTemplate.queryForObject("SELECT last_used_at FROM mcp.access_tokens WHERE token_hash = ?", java.sql.Timestamp::class.java, McpTokenService.hash(token))

        rpc(token, "ping")
        val first = lastUsed()
        Thread.sleep(50)
        rpc(token, "ping")

        assertThat(first).isNotNull()
        assertThat(lastUsed()).isEqualTo(first)
    }

    @Test
    fun `migrating the mcp schema again changes nothing`() {
        val before = jdbcTemplate.queryForObject("SELECT count(*) FROM mcp.flyway_schema_history", Int::class.java)
        val user = newUser()
        val token = mint(user)

        migrator.migrate()
        migrator.migrate()

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM mcp.flyway_schema_history", Int::class.java)).isEqualTo(before)
        assertThat(rpc(token, "ping").status).isEqualTo(200) // and what was there still works
    }

    @Test
    fun `the answer to creating a token has exactly the fields the app reads`() {
        val user = newUser()

        val created = session(HttpMethod.POST, "/mcp-tokens", user, """{"name":"shape"}""").body!!

        assertThat(created.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "name", "prefix", "access", "status", "created_at", "expires_at", "token")
        assertThat(UUID.fromString(created["id"].asText())).isNotNull()
    }
}
