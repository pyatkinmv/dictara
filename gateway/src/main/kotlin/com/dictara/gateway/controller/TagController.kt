package com.dictara.gateway.controller

import com.dictara.gateway.repository.TagRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** The user's tag library. */
@RestController
class TagController(private val tagRepository: TagRepository) {

    data class TagResponse(val id: UUID, val label: String)

    /**
     * Every tag of the user, oldest first: what the list's filter offers. Only the id and the name for now;
     * the library screen adds the description, the transcriptions using the tag and editing (integration
     * iteration 4).
     */
    @GetMapping("/tags")
    fun tags(servletRequest: HttpServletRequest): List<TagResponse> {
        val userId = servletRequest.getAttribute("authenticatedUserId") as UUID?
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        return tagRepository.findByUserIdOrderByCreatedAtAscNameAsc(userId).map { TagResponse(it.id!!, it.name) }
    }
}
