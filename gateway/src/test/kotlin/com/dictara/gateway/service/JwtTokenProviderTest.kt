package com.dictara.gateway.service

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.UUID

/** Plain unit tests, no Spring context needed — [JwtTokenProvider] takes its secret and expiry as plain
 *  constructor args. */
class JwtTokenProviderTest {

    private val jwt = JwtTokenProvider(secret = "a".repeat(40), expiryDays = 30)

    @Test
    fun `a session token validates back to the user it was issued for`() {
        val userId = UUID.randomUUID()
        assertThat(jwt.validate(jwt.issue(userId))).isEqualTo(userId)
    }

    @Test
    fun `an audio token validates back to the user, but only for the submission it was scoped to`() {
        val userId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val otherSubmissionId = UUID.randomUUID()
        val token = jwt.issueAudioToken(userId, submissionId)

        assertThat(jwt.validateAudioToken(token, submissionId)).isEqualTo(userId)
        assertThat(jwt.validateAudioToken(token, otherSubmissionId)).isNull()
    }

    @Test
    fun `an audio token is refused by the ordinary session validator, and vice versa`() {
        val userId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()

        assertThat(jwt.validate(jwt.issueAudioToken(userId, submissionId))).isNull()
        assertThat(jwt.validateAudioToken(jwt.issue(userId), submissionId)).isNull()
    }

    @Test
    fun `garbage is rejected by both validators, not thrown`() {
        val submissionId = UUID.randomUUID()
        assertThat(jwt.validate("not.a.jwt")).isNull()
        assertThat(jwt.validateAudioToken("not.a.jwt", submissionId)).isNull()
    }

    @Test
    fun `a session token past its expiry is rejected, not just malformed ones`() {
        val alreadyExpired = JwtTokenProvider(secret = "a".repeat(40), expiryDays = -1)
        assertThat(alreadyExpired.validate(alreadyExpired.issue(UUID.randomUUID()))).isNull()
    }

    @Test
    fun `an audio token past its TTL is rejected`() {
        // issueAudioToken's TTL is a fixed 45-minute constant, not an injectable field like expiryDays — so
        // this hand-builds a token with the same secret and shape but an expiration in the past, the way an
        // audio link opened well after the page was left idle would arrive.
        val secret = "a".repeat(40)
        val userId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val expiredToken = Jwts.builder()
            .subject(userId.toString())
            .claim("aud_sid", submissionId.toString())
            .issuedAt(Date(System.currentTimeMillis() - 2 * 3_600_000))
            .expiration(Date(System.currentTimeMillis() - 3_600_000))
            .signWith(Keys.hmacShaKeyFor(secret.toByteArray()))
            .compact()

        assertThat(JwtTokenProvider(secret = secret, expiryDays = 30).validateAudioToken(expiredToken, submissionId)).isNull()
    }
}
