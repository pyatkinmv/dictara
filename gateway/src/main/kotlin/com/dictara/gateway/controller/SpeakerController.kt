package com.dictara.gateway.controller

import com.dictara.gateway.service.SpeakerRef
import com.dictara.gateway.service.SpeakerService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** The user's speaker library. Which speaker says what in a transcript is set elsewhere (later iterations). */
@RestController
class SpeakerController(private val speakerService: SpeakerService) {

    data class DeleteResponse(val affectedTranscriptions: Int)

    /**
     * The whole library in creation order: each speaker with description, color, in how many transcriptions
     * they are heard, for how long (`speaking_s`, seconds) and the titles of the latest two.
     */
    @GetMapping("/speakers")
    fun library(request: HttpServletRequest): SpeakerService.Library = speakerService.library(userId(request))

    /** Body `{name, description?}`. 400 for a bad name, 409 when the user already has a speaker of that name (any case). */
    @PostMapping("/speakers")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: Map<String, String?>, request: HttpServletRequest): SpeakerRef =
        speakerService.create(userId(request), body["name"], body["description"])

    /** Body `{name?, description?}`; what is left out stays as it is. */
    @PutMapping("/speakers/{id}")
    fun update(@PathVariable id: String, @RequestBody body: Map<String, String?>, request: HttpServletRequest): SpeakerRef =
        speakerService.update(userId(request), uuid(id), body["name"], body["description"], descriptionGiven = "description" in body)

    /** Removes the speaker from the library and from every transcription; answers how many transcriptions that was. */
    @DeleteMapping("/speakers/{id}")
    fun delete(@PathVariable id: String, request: HttpServletRequest): DeleteResponse =
        DeleteResponse(speakerService.delete(userId(request), uuid(id)))

    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** An id that is not a UUID names nothing, so it is "not found", not a malformed request. */
    private fun uuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
}
