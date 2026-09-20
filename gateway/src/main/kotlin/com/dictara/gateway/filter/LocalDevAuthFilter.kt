package com.dictara.gateway.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** The user that owns the development data (created by `db/local/R__seed.sql`). */
object LocalDevUser {
    val ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
}

/**
 * Lets the web client work without logging in during local development: a request that carries no
 * (valid) token is treated as coming from [LocalDevUser]. A valid token still wins, because
 * [JwtAuthFilter] overwrites the attribute whichever of the two filters runs first.
 *
 * Only exists under the `local` profile; production never authenticates anyone this way.
 */
@Profile("local")
@Component
class LocalDevAuthFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        if (req.getAttribute("authenticatedUserId") == null) {
            req.setAttribute("authenticatedUserId", LocalDevUser.ID)
        }
        chain.doFilter(req, res)
    }
}
