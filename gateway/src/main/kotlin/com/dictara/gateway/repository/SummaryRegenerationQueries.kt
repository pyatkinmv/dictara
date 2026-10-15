package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Where a transcription stands with "regenerate summary" (`submissions.summary_regeneration`): free, running or
 * used. Each change is one conditional UPDATE, so two clicks at once can never both start a run.
 */
@Repository
class SummaryRegenerationQueries(private val jdbc: NamedParameterJdbcTemplate) {

    data class State(val state: String?, val at: Instant?)

    /** Takes the one regeneration of a finished transcription; false when it is already running or was used. */
    fun claim(submissionId: UUID): Boolean = jdbc.update(
        "UPDATE submissions SET summary_regeneration = 'running' WHERE id = :id AND status = 'done' AND summary_regeneration IS NULL",
        mapOf("id" to submissionId),
    ) == 1

    /** The run succeeded: the regeneration is used up. */
    fun finish(submissionId: UUID) {
        jdbc.update(
            "UPDATE submissions SET summary_regeneration = 'done', summary_regenerated_at = now() WHERE id = :id AND summary_regeneration = 'running'",
            mapOf("id" to submissionId),
        )
    }

    /** The run failed: give the regeneration back. */
    fun release(submissionId: UUID) {
        jdbc.update(
            "UPDATE submissions SET summary_regeneration = NULL WHERE id = :id AND summary_regeneration = 'running'",
            mapOf("id" to submissionId),
        )
    }

    /** Runs that were in progress when the process stopped will never finish: give them back. */
    fun releaseAllRunning(): Int = jdbc.update("UPDATE submissions SET summary_regeneration = NULL WHERE summary_regeneration = 'running'", emptyMap<String, Any>())

    fun stateOf(submissionId: UUID): State = jdbc.query(
        "SELECT summary_regeneration, summary_regenerated_at FROM submissions WHERE id = :id",
        mapOf("id" to submissionId),
    ) { rs, _ -> State(rs.getString("summary_regeneration"), rs.getTimestamp("summary_regenerated_at")?.toInstant()) }.firstOrNull() ?: State(null, null)
}
