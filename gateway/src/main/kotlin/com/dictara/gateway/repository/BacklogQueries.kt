package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** What the backlog run needs to know about a batch of transcriptions before it asks Gemini about them. */
@Repository
class BacklogQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** [phrases] and [chars] are those of the transcript (0 when there is none); [backfilledAt] is when the backlog run did it. */
    data class Row(val id: UUID, val userId: UUID, val status: String, val backfilledAt: Instant?, val phrases: Int, val chars: Long)

    /** The rows of the ids that exist; an id that is not in the result is not a transcription. */
    fun rows(ids: Collection<UUID>): Map<UUID, Row> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            SELECT s.id, s.user_id, s.status, s.analysis_backfilled_at, COALESCE(g.n, 0) AS phrases, COALESCE(g.chars, 0) AS chars
            FROM submissions s
            LEFT JOIN transcripts t ON t.submission_id = s.id
            LEFT JOIN LATERAL (SELECT count(*) AS n, sum(length(text)) AS chars FROM transcript_segments WHERE transcript_id = t.id) g ON true
            WHERE s.id IN (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            Row(
                id = rs.getObject("id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                status = rs.getString("status"),
                backfilledAt = rs.getTimestamp("analysis_backfilled_at")?.toInstant(),
                phrases = rs.getInt("phrases"),
                chars = rs.getLong("chars"),
            )
        }.associateBy { it.id }
    }
}
