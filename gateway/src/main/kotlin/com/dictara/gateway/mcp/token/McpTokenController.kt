package com.dictara.gateway.mcp.token

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Creating, listing and revoking one's own MCP tokens (`/mcp-tokens`).
 *
 * Only a signed-in session can do this (the app's JWT): an MCP token is not accepted here, so a token can neither
 * mint another token nor extend its own life.
 */
@RestController
@RequestMapping("/mcp-tokens")
class McpTokenController(private val tokens: McpTokenService) {

    data class CreateRequest(val name: String? = null, val access: String? = null)

    /** The created token with its secret, which is shown this one time and cannot be asked for again. */
    data class CreatedResponse(
        val id: UUID,
        val name: String,
        val prefix: String,
        val access: String,
        val status: String,
        val createdAt: Instant,
        val expiresAt: Instant,
        val token: String,
    )

    /** [mcpAvailable]: whether the user's plan includes MCP access; when it does not, they cannot make tokens (and the ones they have do not work). */
    data class ListResponse(val tokens: List<TokenView>, val mcpAvailable: Boolean)

    /** Body `{name, access}`, `access` being `read` (the default) or `write`. The lifetime is fixed, see [McpTokenService]. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: CreateRequest, request: HttpServletRequest): CreatedResponse {
        val userId = userId(request)
        val access = if (body.access == null) McpAccess.READ else McpAccess.parse(body.access)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown access '${body.access}'; expected one of ${McpAccess.entries.map { it.param }}")
        val created = try {
            tokens.create(userId, body.name, access)
        } catch (e: InvalidTokenNameException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: McpNotInPlanException) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, e.message)
        } catch (e: TokenLimitReachedException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
        }
        val t = created.token
        return CreatedResponse(t.id, t.name, t.prefix, t.access, t.status, t.createdAt, t.expiresAt, created.secret)
    }

    /** The user's tokens that are not revoked, ended ones included (the app shows them as expired). No secrets. */
    @GetMapping
    fun list(request: HttpServletRequest): ListResponse {
        val userId = userId(request)
        return ListResponse(tokens.list(userId), tokens.availableTo(userId))
    }

    /** Revokes a token (or removes an ended one from the list). 404 for someone else's token and for one that is gone. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id: String, request: HttpServletRequest) {
        val userId = userId(request)
        val tokenId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found")
        if (!tokens.revoke(userId, tokenId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found")
    }

    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
