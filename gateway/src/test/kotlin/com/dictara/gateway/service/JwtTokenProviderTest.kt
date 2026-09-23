package com.dictara.gateway.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
