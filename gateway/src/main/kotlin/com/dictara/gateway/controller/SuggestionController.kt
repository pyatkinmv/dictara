package com.dictara.gateway.controller

import com.dictara.gateway.service.SuggestionService
import com.dictara.gateway.service.Suggestions
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Settling what the AI proposed for a transcription (its `suggestions` in `GET /transcriptions/{id}`).
 * Every call answers what is still proposed: `{"suggestions": {...}}`, or `{}` when nothing is left.
 * 404 for someone else's transcription or a proposal that is not (or no longer) pending, 409 while there is no transcript.
 */
@RestController
class SuggestionController(private val suggestionService: SuggestionService) {

    data class Left(val suggestions: Suggestions?)

    /** Renames the transcription as proposed, puts all the proposed tags on it and settles every proposed voice. */
    @PostMapping("/transcriptions/{id}/suggestions/accept-all")
    fun acceptAll(@PathVariable id: String, request: HttpServletRequest) =
        Left(suggestionService.acceptAll(userId(request), uuid(id)))

    /** Rejects everything still proposed; nothing is changed on the transcription. */
    @PostMapping("/transcriptions/{id}/suggestions/dismiss-all")
    fun dismissAll(@PathVariable id: String, request: HttpServletRequest) =
        Left(suggestionService.dismissAll(userId(request), uuid(id)))

    @PostMapping("/transcriptions/{id}/suggestions/tags/{label}/accept")
    fun acceptTag(@PathVariable id: String, @PathVariable label: String, request: HttpServletRequest) =
        Left(suggestionService.acceptTag(userId(request), uuid(id), label))

    @PostMapping("/transcriptions/{id}/suggestions/tags/{label}/reject")
    fun rejectTag(@PathVariable id: String, @PathVariable label: String, request: HttpServletRequest) =
        Left(suggestionService.rejectTag(userId(request), uuid(id), label))

    /** Makes the voice (`SPEAKER_01`) the proposed speaker in this transcription; the speaker is created in the library now if they are new. */
    @PostMapping("/transcriptions/{id}/suggestions/speakers/{voice}/accept")
    fun acceptSpeaker(@PathVariable id: String, @PathVariable voice: String, request: HttpServletRequest) =
        Left(suggestionService.acceptSpeaker(userId(request), uuid(id), voice))

    @PostMapping("/transcriptions/{id}/suggestions/speakers/{voice}/reject")
    fun rejectSpeaker(@PathVariable id: String, @PathVariable voice: String, request: HttpServletRequest) =
        Left(suggestionService.rejectSpeaker(userId(request), uuid(id), voice))

    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun uuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
}
