package com.dictara.gateway.filter

import com.dictara.gateway.service.JwtTokenProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(private val jwt: JwtTokenProvider) : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val header = req.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val userId = jwt.validate(header.removePrefix("Bearer ").trim())
            if (userId != null) req.setAttribute("authenticatedUserId", userId)
        }
        chain.doFilter(req, res)
    }
}
