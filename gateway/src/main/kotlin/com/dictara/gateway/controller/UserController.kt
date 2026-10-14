package com.dictara.gateway.controller

import com.dictara.gateway.plan.PlanService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** The signed-in user's own plan and usage, for the sidebar (`api-gaps.md` §2.6). */
@RestController
class UserController(private val planService: PlanService) {

    data class UsageResponse(val used: Int, val max: Int?, val unit: String)
    data class MeResponse(val planName: String, val usage: UsageResponse)

    /** No name or id here: the display name already comes from the login itself (the JWT's user), and nothing
     *  today needs the id. Add them back if a screen ends up needing them. */
    @GetMapping("/users/me")
    fun me(request: HttpServletRequest): MeResponse {
        val info = planService.info(userId(request))
        return MeResponse(info.planDisplayName, UsageResponse(info.usage.used, info.usage.max, info.usage.unit))
    }

    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
