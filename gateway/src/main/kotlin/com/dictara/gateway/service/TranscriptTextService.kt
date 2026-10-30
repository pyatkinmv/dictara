package com.dictara.gateway.service

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Corrects the text of one phrase of a transcript (`transcript_segments.text`). Only the text: the phrase keeps its
 * time, its voice and its speaker mark, and nothing is worked out again (the summary, the title and the tags stay as
 * they were). The original is not kept.
 */
@Service
class TranscriptTextService(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Sets the text of phrase [index] (from 0) of the user's transcription [submissionId] and returns the text as it
     * was stored (trimmed). 400 for an empty or too long text; 404 when the transcription is someone else's, has no
     * transcript, or has no such phrase.
     */
    @Transactional
    fun editPhrase(userId: UUID, submissionId: UUID, index: Int, rawText: String?): String {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A phrase can't be empty")
        if (text.length > MAX_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A phrase can be at most $MAX_LENGTH characters")
        }
        if (text.contains('\u0000')) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The text has a character that cannot be saved")

        val transcriptId = jdbc.query(
            """
            SELECT t.id FROM transcripts t JOIN submissions s ON s.id = t.submission_id
            WHERE s.id = :submission AND s.user_id = :user
            """.trimIndent(),
            mapOf("submission" to submissionId, "user" to userId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")

        val changed = jdbc.update(
            "UPDATE transcript_segments SET text = :text WHERE transcript_id = :transcript AND idx = :idx",
            mapOf("text" to text, "transcript" to transcriptId, "idx" to index),
        )
        if (changed == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Phrase not found")

        // The old JSON cell is still written next to the rows (see TranscriptEntity), so it is kept in step.
        jdbc.update(
            """
            UPDATE transcripts
            SET segments = jsonb_set(segments, ARRAY[CAST(:idx AS text), 'text'], to_jsonb(CAST(:text AS text)))
            WHERE id = :transcript AND jsonb_typeof(segments) = 'array' AND jsonb_array_length(segments) > :idx
            """.trimIndent(),
            mapOf("text" to text, "transcript" to transcriptId, "idx" to index),
        )
        return text
    }

    companion object {
        const val MAX_LENGTH = 5000
    }
}
