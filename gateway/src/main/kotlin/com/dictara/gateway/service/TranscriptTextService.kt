package com.dictara.gateway.service

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Corrects the text of phrases of a transcript (`transcript_segments.text`). Only the text: a phrase keeps its
 * time, its voice and its speaker mark, and nothing is worked out again (the summary, the title and the tags stay as
 * they were). The original is not kept.
 */
@Service
class TranscriptTextService(private val jdbc: NamedParameterJdbcTemplate) {

    /** One correction made: the phrase, its text before and the text now stored. */
    data class Edit(val index: Int, val before: String, val after: String)

    /**
     * Sets the text of phrase [index] (from 0) of the user's transcription [submissionId] and returns the text as it
     * was stored (trimmed). 400 for an empty or too long text; 404 when the transcription is someone else's, has no
     * transcript, or has no such phrase.
     */
    @Transactional
    fun editPhrase(userId: UUID, submissionId: UUID, index: Int, rawText: String?): String =
        editPhrases(userId, submissionId, listOf(index to rawText)).single().after

    /**
     * Corrects several phrases at once, all or none: one bad phrase (empty text, no such phrase) refuses the whole
     * batch and nothing is changed. A phrase given twice gets the last text. Returns each correction with the text
     * it replaced (in the order of the first mention), so that a caller can show or undo it.
     */
    @Transactional
    fun editPhrases(userId: UUID, submissionId: UUID, edits: List<Pair<Int, String?>>): List<Edit> {
        if (edits.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give at least one phrase")
        val cleaned = LinkedHashMap<Int, String>()
        for ((index, rawText) in edits) cleaned[index] = clean(rawText)

        val transcriptId = jdbc.query(
            """
            SELECT t.id FROM transcripts t JOIN submissions s ON s.id = t.submission_id
            WHERE s.id = :submission AND s.user_id = :user
            """.trimIndent(),
            mapOf("submission" to submissionId, "user" to userId),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")

        return cleaned.map { (index, text) ->
            val before = jdbc.query(
                "SELECT text FROM transcript_segments WHERE transcript_id = :transcript AND idx = :idx FOR UPDATE",
                mapOf("transcript" to transcriptId, "idx" to index),
            ) { rs, _ -> rs.getString("text") }.firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Phrase not found")
            jdbc.update(
                "UPDATE transcript_segments SET text = :text WHERE transcript_id = :transcript AND idx = :idx",
                mapOf("text" to text, "transcript" to transcriptId, "idx" to index),
            )
            // The old JSON cell is still written next to the rows (see TranscriptEntity), so it is kept in step.
            jdbc.update(
                """
                UPDATE transcripts
                SET segments = jsonb_set(segments, ARRAY[CAST(:idx AS text), 'text'], to_jsonb(CAST(:text AS text)))
                WHERE id = :transcript AND jsonb_typeof(segments) = 'array' AND jsonb_array_length(segments) > :idx
                """.trimIndent(),
                mapOf("text" to text, "transcript" to transcriptId, "idx" to index),
            )
            Edit(index, before, text)
        }
    }

    private fun clean(rawText: String?): String {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A phrase can't be empty")
        if (text.length > MAX_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A phrase can be at most $MAX_LENGTH characters")
        }
        if (text.contains('\u0000')) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The text has a character that cannot be saved")
        return text
    }

    companion object {
        const val MAX_LENGTH = 5000
    }
}
