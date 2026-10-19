package com.dictara.gateway.controller

import com.dictara.gateway.service.LibraryView
import com.dictara.gateway.service.TagRef
import com.dictara.gateway.service.TagService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** The user's tag library, and the tags on a transcription. */
@RestController
class TagController(private val tagService: TagService) {

    data class DeleteResponse(val affectedTranscriptions: Int)

    /**
     * The library in creation order. `view=full` (the default): each tag with its description, color, how many
     * transcriptions carry it and the latest two of them, plus how many transcriptions carry any tag at all.
     * `view=brief`: only `id`, `label`, `color` and `description`, which is all a menu or a filter needs. Any other
     * view is a 400.
     */
    @GetMapping("/tags")
    fun library(@RequestParam(required = false) view: String?, request: HttpServletRequest): Any {
        val userId = userId(request)
        return when (LibraryView.parse(view) ?: throw unknownView(view)) {
            LibraryView.FULL -> tagService.library(userId)
            LibraryView.BRIEF -> tagService.briefLibrary(userId)
        }
    }

    private fun unknownView(view: String?) =
        ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown view '$view'; expected one of ${LibraryView.names}")

    /** Body `{label, description?}`. 400 for a bad name, 409 when the user already has a tag of that name. */
    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: Map<String, String?>, request: HttpServletRequest): TagRef =
        tagService.create(userId(request), body["label"], body["description"])

    /** Body `{label?, description?}`; what is left out stays as it is. */
    @PutMapping("/tags/{id}")
    fun update(@PathVariable id: String, @RequestBody body: Map<String, String?>, request: HttpServletRequest): TagRef =
        tagService.update(userId(request), uuid(id), body["label"], body["description"], descriptionGiven = "description" in body)

    /** Removes the tag from the library and from every transcription; answers how many transcriptions that was. */
    @DeleteMapping("/tags/{id}")
    fun delete(@PathVariable id: String, request: HttpServletRequest): DeleteResponse =
        DeleteResponse(tagService.delete(userId(request), uuid(id)))

    /** Body `{tag_id}` for a tag of the library, or `{label, description?}` (created first if it is new). */
    @PostMapping("/transcriptions/{id}/tags")
    fun attach(@PathVariable id: String, @RequestBody body: Map<String, String?>, request: HttpServletRequest): TagRef {
        val tagId = body["tag_id"]?.let { uuid(it) }
        if (tagId == null && body["label"] == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give either tag_id or label")
        }
        return tagService.attach(userId(request), uuid(id), tagId, body["label"], body["description"])
    }

    /** Takes the tag off this transcription only; it stays in the library. */
    @DeleteMapping("/transcriptions/{id}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun detach(@PathVariable id: String, @PathVariable tagId: String, request: HttpServletRequest) =
        tagService.detach(userId(request), uuid(id), uuid(tagId))

    private fun userId(request: HttpServletRequest): UUID =
        request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** An id that is not a UUID names nothing, so it is "not found", not a malformed request. */
    private fun uuid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
}
