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
        UUID.fromString(
            Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).payload.subject
        )
    }.getOrNull()
}
