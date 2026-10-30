package com.dictara.gateway.controller

import com.dictara.gateway.service.TranscriptTextService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * `PUT /transcriptions/{id}/segments/{index}` with `{"text": "..."}`: corrects the text of one phrase (numbered from 0, as
 * in `segments` of the detail). Answers `{"text": "..."}`, the text as stored (trimmed). 400 for an empty or too long text;
 * 404 for someone else's transcription or a phrase that is not there. Only the signed-in app does this: an MCP token cannot.
 */
@RestController
class TranscriptTextController(private val texts: TranscriptTextService) {

    data class EditPhraseRequest(val text: String? = null)
    data class EditPhraseResponse(val text: String)

    @PutMapping("/transcriptions/{id}/segments/{index}")
    fun edit(
        @PathVariable id: String,
        @PathVariable index: String,
        @RequestBody body: EditPhraseRequest,
        request: HttpServletRequest,
    ): EditPhraseResponse {
        val userId = request.getAttribute("authenticatedUserId") as UUID? ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        val phrase = index.toIntOrNull()?.takeIf { it >= 0 } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Phrase not found")
        return EditPhraseResponse(texts.editPhrase(userId, submissionId, phrase, body.text))
    }
}
