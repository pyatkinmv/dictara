package com.dictara.gateway.mcp.token

import com.dictara.gateway.mcp.McpProperties
import com.dictara.gateway.plan.PlanService
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** What a token may do. [WRITE] includes [READ]. Paid operations are not a right of any token: they are never allowed. */
enum class McpAccess(val param: String) {
    READ("read"),
    WRITE("write"),
    ;

    /** True when a token with this access may use something that needs [needed]. */
    fun allows(needed: McpAccess): Boolean = ordinal >= needed.ordinal

    companion object {
        fun parse(value: String?): McpAccess? = entries.firstOrNull { it.param == value }
    }
}

/** Where a token stands: working, working but ending soon, or ended. A revoked token is not listed at all. */
enum class TokenStatus(val param: String) { ACTIVE("active"), EXPIRING("expiring"), EXPIRED("expired") }

/** A token as the user sees it in the list. The secret is not in here: only the first characters, to tell tokens apart. */
data class TokenView(
    val id: UUID,
    val name: String,
    val prefix: String,
    val access: String,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
)

/** A token just created: the one time its secret is shown. */
data class CreatedToken(val token: TokenView, val secret: String)

/** Who is calling, as worked out from a valid token. The user comes only from here, never from the request. */
data class McpPrincipal(val userId: UUID, val tokenId: UUID, val tokenName: String, val access: McpAccess)

sealed interface TokenCheck {
    data class Valid(val principal: McpPrincipal) : TokenCheck
    data object Expired : TokenCheck

    /** A working token whose owner's plan does not (any longer) include MCP access. */
    data object NotInPlan : TokenCheck
    data object Invalid : TokenCheck
}

class TokenLimitReachedException(val max: Int) : RuntimeException("You already have $max working tokens; revoke one first")

class McpNotInPlanException : RuntimeException("MCP access is not included in your plan")

class InvalidTokenNameException : RuntimeException("A token needs a name of 1 to ${McpTokenService.MAX_NAME} characters")

/**
 * The personal access tokens of MCP (`mcp.access_tokens`).
 *
 * A token is `dtr_` and 43 random characters. Only its SHA-256 is stored, so a leaked database does not leak working
 * tokens, and the secret can be shown only once, at creation. Looking a token up is a lookup by that hash.
 */
@Service
class McpTokenService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: McpProperties,
    private val plans: PlanService,
) {
    private val random = SecureRandom()

    /** Whether the user's plan includes MCP access: what lets them make tokens, and lets their tokens work. */
    fun availableTo(userId: UUID): Boolean = plans.mcpAccess(userId)

    fun create(userId: UUID, rawName: String?, access: McpAccess): CreatedToken {
        if (!availableTo(userId)) throw McpNotInPlanException()
        val name = rawName?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME) throw InvalidTokenNameException()
        val working = jdbc.queryForObject(
            "SELECT count(*) FROM mcp.access_tokens WHERE user_id = :user AND revoked_at IS NULL AND expires_at > now()",
            mapOf("user" to userId), Int::class.java,
        ) ?: 0
        if (working >= props.maxActiveTokens) throw TokenLimitReachedException(props.maxActiveTokens)

        val secret = newSecret()
        val id = UUID.randomUUID()
        val expiresAt = Instant.now().plus(Duration.ofDays(props.tokenTtlDays))
        jdbc.update(
            """
            INSERT INTO mcp.access_tokens (id, user_id, name, token_hash, token_prefix, access, expires_at)
            VALUES (:id, :user, :name, :hash, :prefix, :access, :expires)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("user", userId).addValue("name", name)
                .addValue("hash", hash(secret)).addValue("prefix", secret.take(PREFIX_LENGTH))
                .addValue("access", access.param).addValue("expires", Timestamp.from(expiresAt)),
        )
        return CreatedToken(list(userId).first { it.id == id }, secret)
    }

    /** The user's tokens that are not revoked, newest first. Ended ones stay, so that the user can see them. */
    fun list(userId: UUID): List<TokenView> = jdbc.query(
        """
        SELECT id, name, token_prefix, access, created_at, expires_at, last_used_at
        FROM mcp.access_tokens WHERE user_id = :user AND revoked_at IS NULL ORDER BY created_at DESC, id
        """.trimIndent(),
        mapOf("user" to userId),
    ) { rs, _ -> view(rs) }

    /** Revokes (or removes from the list, if it had ended) a token of this user. False when it is not theirs or is gone. */
    fun revoke(userId: UUID, tokenId: UUID): Boolean = jdbc.update(
        "UPDATE mcp.access_tokens SET revoked_at = now() WHERE id = :id AND user_id = :user AND revoked_at IS NULL",
        mapOf("id" to tokenId, "user" to userId),
    ) > 0

    /** Who is behind [raw], if it is a token that works. */
    fun authenticate(raw: String): TokenCheck {
        if (!raw.startsWith(PREFIX) || raw.length != TOKEN_LENGTH) return TokenCheck.Invalid
        val found = jdbc.query(
            "SELECT id, user_id, name, access, expires_at FROM mcp.access_tokens WHERE token_hash = :hash AND revoked_at IS NULL",
            mapOf("hash" to hash(raw)),
        ) { rs, _ ->
            Triple(
                McpPrincipal(
                    rs.getObject("user_id", UUID::class.java), rs.getObject("id", UUID::class.java),
                    rs.getString("name"), McpAccess.parse(rs.getString("access")) ?: McpAccess.READ,
                ),
                rs.getTimestamp("expires_at").toInstant(), 0,
            )
        }.firstOrNull() ?: return TokenCheck.Invalid
        if (!found.second.isAfter(Instant.now())) return TokenCheck.Expired
        // Asked at every call, not when the token is made: a plan that changes changes this at once, and the token
        // (kept, not revoked) works again when the plan includes MCP again.
        if (!availableTo(found.first.userId)) return TokenCheck.NotInPlan
        // Not on every call: a minute of precision is enough for "last used" and spares the table.
        jdbc.update(
            "UPDATE mcp.access_tokens SET last_used_at = now() WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')",
            mapOf("id" to found.first.tokenId),
        )
        return TokenCheck.Valid(found.first)
    }

    private fun view(rs: ResultSet): TokenView {
        val expiresAt = rs.getTimestamp("expires_at").toInstant()
        return TokenView(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            prefix = rs.getString("token_prefix"),
            access = rs.getString("access"),
            status = status(expiresAt).param,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = expiresAt,
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
        )
    }

    private fun status(expiresAt: Instant, now: Instant = Instant.now()): TokenStatus = when {
        !expiresAt.isAfter(now) -> TokenStatus.EXPIRED
        expiresAt.isBefore(now.plus(Duration.ofDays(props.expiringSoonDays))) -> TokenStatus.EXPIRING
        else -> TokenStatus.ACTIVE
    }

    private fun newSecret(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PREFIX = "dtr_"
        const val PREFIX_LENGTH = 8
        const val TOKEN_LENGTH = 4 + 43
        const val MAX_NAME = 80

        fun hash(secret: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    }
}
