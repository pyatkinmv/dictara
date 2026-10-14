package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Who speaks in a transcript. Two layers, both pointing at the speaker library:
 *  - `transcript_voices`: this diarizer voice (`SPEAKER_01`) is that speaker, in this transcript;
 *  - `transcript_segments.speaker_id`: this one phrase is said by that speaker, whatever its voice says.
 * A phrase's speaker is its own, else its voice's, else nobody ("Speaker N" on screen).
 */
@Repository
class TranscriptSpeakerQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** A phrase with the two facts the assignment rules need. */
    data class Phrase(val idx: Int, val voice: String?, val speakerId: UUID?)

    /** A speaker as the transcript page draws them. */
    data class SpeakerInfo(val id: UUID, val name: String)

    /** A phrase for the transcript page; [own] is the speaker set on the phrase itself, if any. */
    data class DetailPhrase(val start: Double, val end: Double, val text: String, val voice: String?, val own: SpeakerInfo?)

    data class Voice(val voice: String, val speaker: SpeakerInfo)

    fun phrases(transcriptId: UUID): List<Phrase> = jdbc.query(
        "SELECT idx, voice, speaker_id FROM transcript_segments WHERE transcript_id = :t ORDER BY idx",
        mapOf("t" to transcriptId),
    ) { rs, _ -> Phrase(rs.getInt("idx"), rs.getString("voice"), rs.getObject("speaker_id", UUID::class.java)) }

    /** voice → speaker id, for this transcript. */
    fun voiceSpeakers(transcriptId: UUID): Map<String, UUID> = jdbc.query(
        "SELECT voice, speaker_id FROM transcript_voices WHERE transcript_id = :t",
        mapOf("t" to transcriptId),
    ) { rs, _ -> rs.getString("voice") to rs.getObject("speaker_id", UUID::class.java) }.toMap()

    /** Sets (or, with a null [speakerId], clears) the speaker of these phrases. */
    fun setPhraseSpeaker(transcriptId: UUID, indexes: Collection<Int>, speakerId: UUID?) {
        if (indexes.isEmpty()) return
        jdbc.update(
            "UPDATE transcript_segments SET speaker_id = :speaker WHERE transcript_id = :t AND idx IN (:idx)",
            MapSqlParameterSource().addValue("speaker", speakerId).addValue("t", transcriptId).addValue("idx", indexes),
        )
    }

    /** Clears the speaker of every phrase of a voice: the voice's own speaker speaks them now. */
    fun clearPhraseSpeakers(transcriptId: UUID, voice: String) {
        jdbc.update(
            "UPDATE transcript_segments SET speaker_id = NULL WHERE transcript_id = :t AND voice = :voice AND speaker_id IS NOT NULL",
            mapOf("t" to transcriptId, "voice" to voice),
        )
    }

    fun setVoiceSpeaker(transcriptId: UUID, voice: String, speakerId: UUID) {
        jdbc.update(
            """
            INSERT INTO transcript_voices (transcript_id, voice, speaker_id) VALUES (:t, :voice, :speaker)
            ON CONFLICT (transcript_id, voice) DO UPDATE SET speaker_id = EXCLUDED.speaker_id
            """.trimIndent(),
            mapOf("t" to transcriptId, "voice" to voice, "speaker" to speakerId),
        )
    }

    /** The phrases of a transcript in order, with the speaker set on each one (if any). */
    fun detailPhrases(transcriptId: UUID): List<DetailPhrase> = jdbc.query(
        """
        SELECT p.start_s, p.end_s, p.text, p.voice, sp.id AS speaker_id, sp.name AS speaker_name
        FROM transcript_segments p
        LEFT JOIN speakers sp ON sp.id = p.speaker_id
        WHERE p.transcript_id = :t
        ORDER BY p.idx
        """.trimIndent(),
        mapOf("t" to transcriptId),
    ) { rs, _ ->
        val speakerId = rs.getObject("speaker_id", UUID::class.java)
        DetailPhrase(
            rs.getDouble("start_s"), rs.getDouble("end_s"), rs.getString("text"), rs.getString("voice"),
            speakerId?.let { SpeakerInfo(it, rs.getString("speaker_name")) },
        )
    }

    /** Which speaker each named voice of the transcript is. */
    fun voices(transcriptId: UUID): List<Voice> = jdbc.query(
        """
        SELECT v.voice, sp.id AS speaker_id, sp.name AS speaker_name
        FROM transcript_voices v JOIN speakers sp ON sp.id = v.speaker_id
        WHERE v.transcript_id = :t
        ORDER BY v.voice
        """.trimIndent(),
        mapOf("t" to transcriptId),
    ) { rs, _ -> Voice(rs.getString("voice"), SpeakerInfo(rs.getObject("speaker_id", UUID::class.java), rs.getString("speaker_name"))) }
}
