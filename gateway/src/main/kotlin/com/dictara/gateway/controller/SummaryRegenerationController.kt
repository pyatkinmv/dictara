package com.dictara.gateway.controller

import com.dictara.gateway.service.SummaryRegenerationService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * `POST /transcriptions/{id}/regenerate-summary`: asks Gemini again about a finished transcription, once.
 * 202 when it started (the detail then says `summary_regeneration: running`, later `used`); 404 for someone else's
 * transcription; 409 when it is not ready, was already regenerated or is being regenerated now.
 */
@RestController
class SummaryRegenerationController(private val regeneration: SummaryRegenerationService) {

    data class Started(val state: String)

    @PostMapping("/transcriptions/{id}/regenerate-summary")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun regenerate(@PathVariable id: String, request: HttpServletRequest): Started {
        val userId = request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
        regeneration.start(userId, submissionId)
        return Started("running")
    }
}
