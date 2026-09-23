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

    /**
     * One transcript as the list sees it. [speakerCount] counts distinct speakers actually present — a
     * phrase's own mark, else its voice's confirmed speaker, else the voice itself as a stand-in identity
     * (matching the detail page's `presentSpeakers`) — never the raw diarizer voice count, which does not
     * shrink when two voices turn out to be the same person, or grow when one phrase is given to someone new.
     */
    data class Row(val submissionId: UUID, val transcriptId: UUID, val title: String?, val speakerCount: Int)

    fun findRows(submissionIds: Collection<UUID>): List<Row> {
        if (submissionIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT t.submission_id,
                   t.id,
                   t.title,
                   (
                       SELECT count(DISTINCT COALESCE(s.speaker_id::text, tv.speaker_id::text, s.voice))
                       FROM transcript_segments s
                       LEFT JOIN transcript_voices tv ON tv.transcript_id = s.transcript_id AND tv.voice = s.voice
                       WHERE s.transcript_id = t.id
                   ) AS speaker_count
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
