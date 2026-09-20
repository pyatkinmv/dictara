package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * What the transcription list needs to know about transcripts, read without loading their segments:
 * a transcript can be hours long, and the list is refreshed every few seconds while something processes.
 */
@Repository
class TranscriptListQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** One transcript as the list sees it. [speakerCount] counts the distinct diarizer labels in the segments. */
    data class Row(val submissionId: UUID, val transcriptId: UUID, val title: String?, val speakerCount: Int)

    fun findRows(submissionIds: Collection<UUID>): List<Row> {
        if (submissionIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT t.submission_id,
                   t.id,
                   t.title,
                   (SELECT count(DISTINCT s.voice) FROM transcript_segments s WHERE s.transcript_id = t.id) AS speaker_count
            FROM transcripts t
            WHERE t.submission_id IN (:ids)
            """.trimIndent(),
            mapOf("ids" to submissionIds),
        ) { rs, _ ->
            Row(
                submissionId = rs.getObject("submission_id", UUID::class.java),
                transcriptId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                speakerCount = rs.getInt("speaker_count"),
            )
        }
    }
}
