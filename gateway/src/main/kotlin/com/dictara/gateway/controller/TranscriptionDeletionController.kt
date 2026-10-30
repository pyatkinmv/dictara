package com.dictara.gateway.controller

import com.dictara.gateway.service.TranscriptionDeletionService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * `DELETE /transcriptions/{id}`: removes the transcription with its transcript, summary, tags, speaker marks and audio
 * file, for good. 204 when done; 404 for someone else's or an unknown id; 409 while it is processed. Only the signed-in
 * app does this: an MCP token cannot (see `docs/mcp.md`).
 */
@RestController
class TranscriptionDeletionController(private val deletion: TranscriptionDeletionService) {

    @DeleteMapping("/transcriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String, request: HttpServletRequest) {
        val userId = request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        deletion.delete(userId, submissionId)
    }
}
