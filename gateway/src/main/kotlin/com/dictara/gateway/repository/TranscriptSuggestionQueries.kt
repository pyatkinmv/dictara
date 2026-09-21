package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * The AI's proposals for a transcript (`transcript_suggestions`). A row is `pending` until the user accepts
 * or rejects it; then it keeps its status, so the same proposal is never made again.
 * The readers give only what is still worth showing: a tag already on the transcript, or a voice that has
 * been given a speaker in the meantime, is no longer a proposal.
 */
@Repository
class TranscriptSuggestionQueries(private val jdbc: NamedParameterJdbcTemplate) {

    data class TagProposal(val tagId: UUID, val name: String)

    /** [speakerId] is set when the name is a speaker the library already had (then [name] is theirs as it is now). */
    data class SpeakerProposal(val voice: String, val name: String, val speakerId: UUID?, val evidence: String?)

    fun insertTitle(transcriptId: UUID, title: String) {
        jdbc.update(
            "INSERT INTO transcript_suggestions (transcript_id, kind, title) VALUES (:t, 'title', :title) ON CONFLICT DO NOTHING",
            mapOf("t" to transcriptId, "title" to title),
        )
    }

    fun insertTag(transcriptId: UUID, tagId: UUID) {
        jdbc.update(
            "INSERT INTO transcript_suggestions (transcript_id, kind, tag_id) VALUES (:t, 'tag', :tag) ON CONFLICT DO NOTHING",
            mapOf("t" to transcriptId, "tag" to tagId),
        )
    }

    fun insertSpeaker(transcriptId: UUID, voice: String, name: String, speakerId: UUID?, evidence: String?) {
        jdbc.update(
            """
            INSERT INTO transcript_suggestions (transcript_id, kind, voice, speaker_name, speaker_id, evidence)
            VALUES (:t, 'speaker', :voice, :name, :speaker, :evidence) ON CONFLICT DO NOTHING
            """.trimIndent(),
            mapOf("t" to transcriptId, "voice" to voice, "name" to name, "speaker" to speakerId, "evidence" to evidence),
        )
    }

    fun pendingTitle(transcriptId: UUID): String? = jdbc.query(
        "SELECT title FROM transcript_suggestions WHERE transcript_id = :t AND kind = 'title' AND status = 'pending'",
        mapOf("t" to transcriptId),
    ) { rs, _ -> rs.getString("title") }.firstOrNull()

    fun pendingTags(transcriptId: UUID): List<TagProposal> = jdbc.query(
        """
        SELECT tg.id, tg.name
        FROM transcript_suggestions s JOIN tags tg ON tg.id = s.tag_id
        WHERE s.transcript_id = :t AND s.kind = 'tag' AND s.status = 'pending'
          AND NOT EXISTS (SELECT 1 FROM transcript_tags tt WHERE tt.transcript_id = s.transcript_id AND tt.tag_id = s.tag_id)
        ORDER BY s.created_at, tg.name
        """.trimIndent(),
        mapOf("t" to transcriptId),
    ) { rs, _ -> TagProposal(rs.getObject("id", UUID::class.java), rs.getString("name")) }

    fun pendingSpeakers(transcriptId: UUID): List<SpeakerProposal> = jdbc.query(
        """
        SELECT s.voice, COALESCE(sp.name, s.speaker_name) AS name, s.speaker_id, s.evidence
        FROM transcript_suggestions s LEFT JOIN speakers sp ON sp.id = s.speaker_id
        WHERE s.transcript_id = :t AND s.kind = 'speaker' AND s.status = 'pending'
          AND NOT EXISTS (SELECT 1 FROM transcript_voices v WHERE v.transcript_id = s.transcript_id AND v.voice = s.voice)
        ORDER BY s.voice
        """.trimIndent(),
        mapOf("t" to transcriptId),
    ) { rs, _ ->
        SpeakerProposal(rs.getString("voice"), rs.getString("name"), rs.getObject("speaker_id", UUID::class.java), rs.getString("evidence"))
    }

    /** Settles the pending title proposal; false when there was none. */
    fun resolveTitle(transcriptId: UUID, status: String): Boolean = resolve(
        "transcript_id = :t AND kind = 'title'", mapOf("t" to transcriptId), status,
    ) > 0

    fun resolveTag(transcriptId: UUID, tagId: UUID, status: String): Boolean = resolve(
        "transcript_id = :t AND kind = 'tag' AND tag_id = :tag", mapOf("t" to transcriptId, "tag" to tagId), status,
    ) > 0

    fun resolveSpeaker(transcriptId: UUID, voice: String, status: String): Boolean = resolve(
        "transcript_id = :t AND kind = 'speaker' AND voice = :voice", mapOf("t" to transcriptId, "voice" to voice), status,
    ) > 0

    /** Settles everything still pending on the transcript. */
    fun resolveAll(transcriptId: UUID, status: String): Int = resolve("transcript_id = :t", mapOf("t" to transcriptId), status)

    private fun resolve(where: String, params: Map<String, Any>, status: String): Int = jdbc.update(
        "UPDATE transcript_suggestions SET status = :status, resolved_at = now() WHERE $where AND status = 'pending'",
        params + ("status" to status),
    )
}
