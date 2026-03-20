package com.dictara.gateway

import com.dictara.gateway.entity.LoginTokenEntity
import com.dictara.gateway.repository.AuthIdentityRepository
import com.dictara.gateway.repository.LoginTokenRepository
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
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class AuthIntegrationTest {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var loginTokenRepo: LoginTokenRepository
    @Autowired lateinit var authIdentityRepo: AuthIdentityRepository

    private val mapper = ObjectMapper()

    companion object {
        @Container @JvmField val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private val jsonHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    private fun loginByUsername(username: String): Map<*, *> {
        val response = rest.postForEntity(
            "/auth/login-by-username",
            HttpEntity(mapOf("telegramUsername" to username), jsonHeaders),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    private fun confirmCallback(token: String, telegramUserId: String, telegramUsername: String? = null) {
        val response = rest.postForEntity(
            "/auth/login-link/confirm-callback",
            HttpEntity(mapOf("token" to token, "telegramUserId" to telegramUserId, "telegramUsername" to telegramUsername), jsonHeaders),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `loginByUsername returns unknown_user for new user`() {
        val r = loginByUsername("new_user_dana")
        assertThat(r["status"]).isEqualTo("unknown_user")
        assertThat(r["bot_url"]).isNotNull()
    }

    @Test
    fun `repeated loginByUsername rejects stale pending tokens`() {
        val r1 = loginByUsername("alice_stale")
        val token1 = UUID.fromString(r1["token"] as String)

        val r2 = loginByUsername("alice_stale")
        val token2 = UUID.fromString(r2["token"] as String)

        assertThat(loginTokenRepo.findById(token1).orElseThrow().rejected).isTrue()
        assertThat(loginTokenRepo.findById(token2).orElseThrow().rejected).isFalse()
    }

    @Test
    fun `confirmCallback sets bot_started true for new user`() {
        val r = loginByUsername("bot_start_eve")
        confirmCallback(r["token"] as String, telegramUserId = "uid_eve", telegramUsername = "bot_start_eve")

        val identity = authIdentityRepo.findByProviderAndProviderUid("telegram", "uid_eve")
        assertThat(identity).isNotNull()
        val botStarted = mapper.readTree(identity!!.metadata ?: "{}").get("bot_started")?.asBoolean()
        assertThat(botStarted).isTrue()
    }

    @Test
    fun `second loginByUsername returns notified after bot confirms new user`() {
        val r1 = loginByUsername("returning_frank")
        confirmCallback(r1["token"] as String, telegramUserId = "uid_frank", telegramUsername = "returning_frank")

        val r2 = loginByUsername("returning_frank")
        assertThat(r2["status"]).isEqualTo("notified")
    }

    @Test
    fun `poll login link returns JWT after confirmation`() {
        val r = loginByUsername("jwt_grace")
        val token = r["token"] as String
        confirmCallback(token, telegramUserId = "uid_grace", telegramUsername = "jwt_grace")

        val pollResp = rest.getForEntity("/auth/login-link/$token", Map::class.java)
        assertThat(pollResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(pollResp.body!!["confirmed"]).isEqualTo(true)
        assertThat(pollResp.body!!["token"] as String?).isNotBlank()
    }

    @Test
    fun `poll login link returns confirmed false when still pending`() {
        val r = loginByUsername("pending_hank")
        val token = r["token"] as String

        val pollResp = rest.getForEntity("/auth/login-link/$token", Map::class.java)
        assertThat(pollResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(pollResp.body!!["confirmed"]).isEqualTo(false)
    }

    @Test
    fun `confirmCallback on expired token returns 410`() {
        val expiredToken = loginTokenRepo.save(LoginTokenEntity(
            token = UUID.randomUUID(),
            pendingUsername = "expired_ivy",
            expiresAt = Instant.now().minusSeconds(1),
        ))

        val response = rest.postForEntity(
            "/auth/login-link/confirm-callback",
            HttpEntity(mapOf("token" to expiredToken.token.toString(), "telegramUserId" to "uid_ivy"), jsonHeaders),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.GONE)
    }
}
