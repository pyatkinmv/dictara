package com.dictara.gateway.repository

import com.dictara.gateway.model.Segment
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * The phrases of transcripts, one row each (`transcript_segments`). Everything that needs the text of a
 * transcript, or a fact about its phrases, reads it here; see docs/local-backend-integration.md, "Итерация 5a".
 *
 * A phrase's [Segment.speaker] is the diarizer's label (`SPEAKER_01`), null without diarization: exactly what
 * the transcriber sent. Who that is in the speaker library is a separate matter (`transcript_voices`).
 */
@Repository
class TranscriptSegmentQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** Stores [segments] as the phrases of the transcript, numbered from 0 in the order given. */
    fun insertAll(transcriptId: UUID, segments: List<Segment>) {
        if (segments.isEmpty()) return
        val rows = segments.mapIndexed { idx, s ->
            MapSqlParameterSource()
                .addValue("transcript", transcriptId)
                .addValue("idx", idx)
                .addValue("start", s.start)
                .addValue("end", s.end)
                .addValue("text", s.text)
                .addValue("voice", s.speaker)
        }
        jdbc.batchUpdate(
            "INSERT INTO transcript_segments (transcript_id, idx, start_s, end_s, text, voice) VALUES (:transcript, :idx, :start, :end, :text, :voice)",
            rows.toTypedArray(),
        )
    }

    /** The phrases of one transcript in order; empty when there is none. */
    fun findByTranscriptId(transcriptId: UUID): List<Segment> = jdbc.query(
        "SELECT start_s, end_s, text, voice FROM transcript_segments WHERE transcript_id = :transcript ORDER BY idx",
        mapOf("transcript" to transcriptId),
    ) { rs, _ -> Segment(rs.getDouble("start_s"), rs.getDouble("end_s"), rs.getString("text"), rs.getString("voice")) }

    /** The phrases of several transcripts, each in order; a transcript without phrases is not in the map. */
    fun findByTranscriptIds(transcriptIds: Collection<UUID>): Map<UUID, List<Segment>> {
        if (transcriptIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<UUID, MutableList<Segment>>()
        jdbc.query(
            "SELECT transcript_id, start_s, end_s, text, voice FROM transcript_segments WHERE transcript_id IN (:ids) ORDER BY transcript_id, idx",
            mapOf("ids" to transcriptIds),
            RowCallbackHandler { rs ->
                result.getOrPut(rs.getObject("transcript_id", UUID::class.java)) { mutableListOf() }
                    .add(Segment(rs.getDouble("start_s"), rs.getDouble("end_s"), rs.getString("text"), rs.getString("voice")))
            },
        )
        return result
    }
}
