package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.util.UUID

/** `GET /users/me`: the plan and usage the sidebar shows (`docs/local-backend-integration.md`, "Итерация 11"). */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class UserProfileIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider

    private val json = ObjectMapper()

    private fun newUser(plan: String = "free") = UUID.randomUUID().also {
        jdbcTemplate.update("INSERT INTO users(id, plan) VALUES (?, ?)", it, plan)
    }

    private fun addSubmissions(user: UUID, count: Int) {
        repeat(count) {
            val audioId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.mp3', 'audio/mp4', 1000, ?, ?)",
                audioId, user, "gs://test/$audioId", "hash-$audioId-$it",
            )
            jdbcTemplate.update(
                "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
                UUID.randomUUID(), user, audioId,
            )
        }
    }

    private fun me(user: UUID?): JsonNode {
        val headers = HttpHeaders().apply { user?.let { setBearerAuth(jwt.issue(it)) } }
        val response = rest.exchange("/users/me", org.springframework.http.HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertThat(response.statusCode.value()).describedAs(response.body ?: "").isEqualTo(HttpStatus.OK.value())
        return json.readTree(response.body)
    }

    @Test
    fun `a free-plan user sees how many of the monthly cap were used`() {
        val user = newUser("free")
        addSubmissions(user, 3)

        val body = me(user)

        assertThat(body["plan_name"].asText()).isEqualTo("Free")
        assertThat(body["usage"]["used"].asInt()).isEqualTo(3)
        assertThat(body["usage"]["max"].asInt()).isEqualTo(10)
        assertThat(body["usage"]["unit"].asText()).isEqualTo("transcriptions")
    }

    @Test
    fun `an anonymous-plan user sees the lifetime cap, not a monthly one`() {
        val user = newUser("anonymous")
        addSubmissions(user, 2)

        val body = me(user)

        assertThat(body["plan_name"].asText()).isEqualTo("Guest")
        assertThat(body["usage"]["used"].asInt()).isEqualTo(2)
        assertThat(body["usage"]["max"].asInt()).isEqualTo(2)
    }

    @Test
    fun `an unlimited-plan user sees a count with no denominator`() {
        val user = newUser("unlimited")
        addSubmissions(user, 14)

        val body = me(user)

        assertThat(body["plan_name"].asText()).isEqualTo("Ultra")
        assertThat(body["usage"]["used"].asInt()).isEqualTo(14)
        assertThat(body["usage"].has("max")).describedAs("NON_NULL Jackson config drops it entirely").isFalse()
    }

    @Test
    fun `a brand new user has used none of the plan yet`() {
        val user = newUser("free")

        val body = me(user)

        assertThat(body["usage"]["used"].asInt()).isZero()
        assertThat(body["usage"]["max"].asInt()).isEqualTo(10)
    }

    @Test
    fun `without a token the request is refused`() {
        val headers = HttpHeaders()
        val response = rest.exchange("/users/me", org.springframework.http.HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `only the caller's own usage is counted, never another user's`() {
        val user = newUser("free")
        val other = newUser("free")
        addSubmissions(other, 5)

        val body = me(user)

        assertThat(body["usage"]["used"].asInt()).isZero()
    }
}
