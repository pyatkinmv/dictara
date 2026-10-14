package com.dictara.gateway.service

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID

@Service
class JwtTokenProvider(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiry-days}") private val expiryDays: Long,
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issue(userId: UUID): String = Jwts.builder()
        .subject(userId.toString())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + expiryDays * 86_400_000L))
        .signWith(key)
        .compact()

    fun validate(token: String): UUID? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        // A narrow audio-playback token (see issueAudioToken) must never unlock the rest of the API, even if
        // someone tries sending it as a normal Authorization header instead of the query param it was meant for.
        if (claims.containsKey(AUDIO_SCOPE_CLAIM)) return@runCatching null
        UUID.fromString(claims.subject)
    }.getOrNull()

    /** A narrow, short-lived token for streaming one transcription's audio (`AudioPlaybackController`). An
     *  HTML `<audio>` element can't send an `Authorization` header, so this rides in the URL's query string
     *  instead — scoped to exactly [submissionId] and expiring in [AUDIO_TOKEN_TTL_SECONDS], unlike the
     *  30-day session token, so a leaked playback link is far less damaging than a leaked session would be
     *  (that gap — the session token itself still can't be revoked before it expires — is tracked in
     *  docs/redesign-plan.md, plan item 12). */
    fun issueAudioToken(userId: UUID, submissionId: UUID): String = Jwts.builder()
        .subject(userId.toString())
        .claim(AUDIO_SCOPE_CLAIM, submissionId.toString())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + AUDIO_TOKEN_TTL_SECONDS * 1000))
        .signWith(key)
        .compact()

    /** The user id if [token] is a well-formed, unexpired audio token minted by [issueAudioToken] for exactly
     *  this [submissionId] — a token minted for one transcription cannot be reused for another's audio. */
    fun validateAudioToken(token: String, submissionId: UUID): UUID? = runCatching {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        if (claims[AUDIO_SCOPE_CLAIM] != submissionId.toString()) return@runCatching null
        UUID.fromString(claims.subject)
    }.getOrNull()

    companion object {
        const val AUDIO_TOKEN_TTL_SECONDS = 45 * 60L
        private const val AUDIO_SCOPE_CLAIM = "aud_sid"
    }
}
