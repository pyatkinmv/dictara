package com.dictara.gateway.controller

import com.dictara.gateway.service.LibraryView
import com.dictara.gateway.service.SpeakerRef
import com.dictara.gateway.service.SpeakerService
import com.dictara.gateway.service.TranscriptSpeakerService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** The user's speaker library, and who speaks in a transcript (the Speaker Picker). */
@RestController
class SpeakerController(
    private val speakerService: SpeakerService,
    private val transcriptSpeakers: TranscriptSpeakerService,
) {

    data class DeleteResponse(val affectedTranscriptions: Int)

    /**
     * The library in creation order. `view=full` (the default): each speaker with description, color, in how many
     * transcriptions they are heard, for how long (`speaking_s`, seconds) and the latest two of them. `view=brief`:
     * only `id`, `name`, `color` and `description`, which is all a menu needs and costs a single cheap query
     * (the times are what makes the full list slow). Any other view is a 400.
     */
    @GetMapping("/speakers")
    fun library(@RequestParam(required = false) view: String?, request: HttpServletRequest): Any {
        val userId = userId(request)
        return when (LibraryView.parse(view) ?: throw unknownView(view)) {
            LibraryView.FULL -> speakerService.library(userId)
            LibraryView.BRIEF -> speakerService.briefLibrary(userId)
        }
    }

    private fun unknownView(view: String?) =
        ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown view '$view'; expected one of ${LibraryView.names}")

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

    /** What the Speaker Picker sends: which phrases, which speaker, and how far it reaches. */
    data class AssignRequest(
        /** `segment` or `all` (everything the user sees under one name; see [TranscriptSpeakerService]). */
        val scope: String?,
        /** Phrase numbers in the transcript, from 0. */
        val segmentIndexes: List<Int>?,
        /** A speaker of the library... */
        val speakerId: String?,
        /** ...or a name: the library speaker of that name, created if there is none. */
        val name: String?,
    )

    /**
     * Makes phrases of a transcription the speaker's (the Speaker Picker). Answers the speaker. 404 for someone
     * else's transcription or speaker, 409 while there is no transcript, 400 for a phrase that does not exist,
     * an unknown scope, or neither a speaker id nor a name.
     */
    @PutMapping("/transcriptions/{id}/speakers")
    fun assign(@PathVariable id: String, @RequestBody body: AssignRequest, request: HttpServletRequest): SpeakerRef {
        val scope = TranscriptSpeakerService.Scope.parse(body.scope)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scope is 'segment' or 'all'")
        return transcriptSpeakers.assign(
            userId(request), uuid(id), scope, body.segmentIndexes.orEmpty().toSet(),
            body.speakerId?.let { uuid(it) }, body.name,
        )
    }


    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** An id that is not a UUID names nothing, so it is "not found", not a malformed request. */
    private fun uuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
}
