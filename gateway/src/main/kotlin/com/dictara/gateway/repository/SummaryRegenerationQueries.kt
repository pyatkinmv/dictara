package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * Where a transcription stands with "regenerate summary": whether a run is going (`summary_regeneration = 'running'`),
 * how many times it has been done (`summary_regenerations`) and when last (`summary_regenerated_at`). Each change is
 * one conditional UPDATE, so two clicks at once can never both start a run, nor go past the plan's limit.
 */
@Repository
class SummaryRegenerationQueries(private val jdbc: NamedParameterJdbcTemplate) {

    data class State(val running: Boolean, val count: Int, val at: Instant?, val error: String? = null)

    /**
     * Takes one regeneration of a finished transcription; false when a run is going or the [limit] of its plan
     * (null: no limit) is used up.
     */
    fun claim(submissionId: UUID, limit: Int?): Boolean = jdbc.update(
        """
        UPDATE submissions SET summary_regeneration = 'running', summary_regeneration_error = NULL
        WHERE id = :id AND status = 'done' AND summary_regeneration IS NULL
          AND (CAST(:limit AS integer) IS NULL OR summary_regenerations < CAST(:limit AS integer))
        """.trimIndent(),
        MapSqlParameterSource("id", submissionId).addValue("limit", limit, Types.INTEGER),
    ) == 1

    /** The run succeeded: it is counted, and the transcription can be regenerated again if the plan allows. */
    fun finish(submissionId: UUID) {
        jdbc.update(
            """
            UPDATE submissions SET summary_regeneration = NULL, summary_regenerations = summary_regenerations + 1, summary_regenerated_at = now()
            WHERE id = :id AND summary_regeneration = 'running'
            """.trimIndent(),
            mapOf("id" to submissionId),
        )
    }

    /** The backlog run is done with the transcription: it is marked, and it is not counted as the user's own regeneration. */
    fun finishBackfill(submissionId: UUID) {
        jdbc.update(
            "UPDATE submissions SET summary_regeneration = NULL, analysis_backfilled_at = now() WHERE id = :id AND summary_regeneration = 'running'",
            mapOf("id" to submissionId),
        )
    }

    /** The user's own run failed: it is not counted, and [message] (for the user, no technical details) stays until the next attempt. */
    fun fail(submissionId: UUID, message: String) {
        jdbc.update(
            "UPDATE submissions SET summary_regeneration = NULL, summary_regeneration_error = :message WHERE id = :id AND summary_regeneration = 'running'",
            mapOf("id" to submissionId, "message" to message.take(300)),
        )
    }

    /** The run failed: it is not counted. */
    fun release(submissionId: UUID) {
        jdbc.update(
            "UPDATE submissions SET summary_regeneration = NULL WHERE id = :id AND summary_regeneration = 'running'",
            mapOf("id" to submissionId),
        )
    }

    /** Runs that were in progress when the process stopped will never finish: give them back. */
    fun releaseAllRunning(): Int = jdbc.update("UPDATE submissions SET summary_regeneration = NULL WHERE summary_regeneration = 'running'", emptyMap<String, Any>())

    fun stateOf(submissionId: UUID): State = jdbc.query(
        "SELECT summary_regeneration, summary_regenerations, summary_regenerated_at, summary_regeneration_error FROM submissions WHERE id = :id",
        mapOf("id" to submissionId),
    ) { rs, _ ->
        State(rs.getString("summary_regeneration") == "running", rs.getInt("summary_regenerations"), rs.getTimestamp("summary_regenerated_at")?.toInstant(), rs.getString("summary_regeneration_error"))
    }
        .firstOrNull() ?: State(false, 0, null)
}
