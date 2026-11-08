package com.dictara.gateway.service

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Lets a person (or an agent working for them) write the summary of a transcription by hand. Nothing is asked of the
 * summarizer: this only stores the text, so it costs nothing. The previous text is not kept, and a later
 * "Regenerate" replaces whatever is written here.
 */
@Service
class SummaryTextService(private val jdbc: NamedParameterJdbcTemplate) {

    data class Written(val before: String?, val after: String)

    /**
     * Replaces the summary of the user's transcription [submissionId] with [rawText] (trimmed; Markdown as the app
     * shows it) and returns what it was and what it is now. 400 for an empty or too long text; 404 for someone else's
     * transcription; 409 while it is not `done` yet or while its summary is being regenerated (that run would
     * overwrite the text a moment later).
     */
    @Transactional
    fun set(userId: UUID, submissionId: UUID, rawText: String?): Written {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A summary can't be empty")
        if (text.length > MAX_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A summary can be at most $MAX_LENGTH characters")
        }
        if (text.contains('\u0000')) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The text has a character that cannot be saved")

        // The row is locked, so a regeneration cannot start between the check and the write.
        val state = jdbc.query(
            "SELECT status, summary_regeneration FROM submissions WHERE id = :id AND user_id = :user FOR UPDATE",
            mapOf("id" to submissionId, "user" to userId),
        ) { rs, _ -> rs.getString("status") to rs.getString("summary_regeneration") }.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        if (state.first != "done") throw ResponseStatusException(HttpStatus.CONFLICT, "The transcription is not ready yet")
        if (state.second == "running") {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The summary is being regenerated; try again when that is done")
        }

        val before = jdbc.query(
            "SELECT text FROM summaries WHERE submission_id = :id",
            mapOf("id" to submissionId),
        ) { rs, _ -> rs.getString("text") }.firstOrNull()
        jdbc.update(
            """
            INSERT INTO summaries (submission_id, text) VALUES (:id, :text)
            ON CONFLICT (submission_id) DO UPDATE SET text = EXCLUDED.text
            """.trimIndent(),
            mapOf("id" to submissionId, "text" to text),
        )
        return Written(before?.takeIf { it.isNotBlank() }, text)
    }

    companion object {
        const val MAX_LENGTH = 20_000
    }
}
