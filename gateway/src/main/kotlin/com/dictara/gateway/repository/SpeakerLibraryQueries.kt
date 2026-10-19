package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * What the speaker library screen shows about each speaker: in how many transcriptions they are heard,
 * for how long, and where.
 *
 * A speaker is in a transcription when they are the speaker of one of its voices (`transcript_voices`) or
 * of single phrases (`transcript_segments.speaker_id`). A phrase is theirs when it is set to them, or has
 * no speaker of its own and its voice is theirs: the same rule the transcript is drawn with.
 */
@Repository
class SpeakerLibraryQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** A speaker with usage: [speakingS] is the summed length of their phrases in seconds. */
    data class Row(
        val id: UUID,
        val name: String,
        val description: String?,
        val transcriptionCount: Int,
        val speakingS: Double,
        val samples: List<LibrarySample>,
    )

    /** A speaker without any usage: what a menu needs. */
    data class BriefRow(val id: UUID, val name: String, val description: String?)

    /** The user's speakers in creation order (ties by name), and nothing else: one cheap query. */
    fun findBrief(userId: UUID): List<BriefRow> = jdbc.query(
        "SELECT id, name, description FROM speakers WHERE user_id = :user ORDER BY created_at ASC, name ASC",
        mapOf("user" to userId),
    ) { rs, _ -> BriefRow(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("description")) }

    /** The user's speakers in creation order (ties by name), each with its [sampleSize] most recent finished transcriptions. */
    fun findLibrary(userId: UUID, sampleSize: Int): List<Row> {
        val usage = USAGE_CTE

        val samples = jdbc.query(
            """
            $usage
            SELECT x.speaker_id, x.job_id, x.title
            FROM (
                SELECT u.speaker_id,
                       s.id AS job_id,
                       COALESCE(t.title, a.original_name) AS title,
                       row_number() OVER (PARTITION BY u.speaker_id ORDER BY s.created_at DESC, s.id DESC) AS rn
                FROM presence u
                JOIN transcripts t ON t.id = u.transcript_id
                JOIN submissions s ON s.id = t.submission_id
                JOIN audio_meta a ON a.id = s.audio_id
                WHERE s.status = 'done'
            ) x
            WHERE x.rn <= :size
            ORDER BY x.speaker_id, x.rn
            """.trimIndent(),
            mapOf("user" to userId, "size" to sampleSize),
        ) { rs, _ ->
            rs.getObject("speaker_id", UUID::class.java) to LibrarySample(rs.getObject("job_id", UUID::class.java), rs.getString("title"))
        }.groupBy({ it.first }, { it.second })

        val speaking: Map<UUID, Double> = jdbc.query(
            """
            SELECT COALESCE(p.speaker_id, v.speaker_id) AS speaker_id, sum(p.end_s - p.start_s) AS seconds
            FROM transcript_segments p
            LEFT JOIN transcript_voices v ON v.transcript_id = p.transcript_id AND v.voice = p.voice
            WHERE COALESCE(p.speaker_id, v.speaker_id) IN (SELECT id FROM speakers WHERE user_id = :user)
            GROUP BY COALESCE(p.speaker_id, v.speaker_id)
            """.trimIndent(),
            mapOf("user" to userId),
        ) { rs, _ -> rs.getObject("speaker_id", UUID::class.java) to rs.getDouble("seconds") }.toMap()

        val counts: Map<UUID, Int> = jdbc.query(
            "$usage SELECT speaker_id, count(*) AS n FROM presence GROUP BY speaker_id",
            mapOf("user" to userId),
        ) { rs, _ -> rs.getObject("speaker_id", UUID::class.java) to rs.getInt("n") }.toMap()

        return jdbc.query(
            "SELECT id, name, description FROM speakers WHERE user_id = :user ORDER BY created_at ASC, name ASC",
            mapOf("user" to userId),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            Row(id, rs.getString("name"), rs.getString("description"), counts[id] ?: 0, speaking[id] ?: 0.0, samples[id] ?: emptyList())
        }
    }

    /** In how many transcriptions the speaker is heard (what deleting them takes them out of). */
    fun countTranscriptions(speakerId: UUID): Int = jdbc.queryForObject(
        """
        SELECT count(*) FROM (
            SELECT transcript_id FROM transcript_voices WHERE speaker_id = :id
            UNION
            SELECT transcript_id FROM transcript_segments WHERE speaker_id = :id
        ) u
        """.trimIndent(),
        mapOf("id" to speakerId),
        Int::class.java,
    ) ?: 0

    companion object {
        /** (transcript, speaker) pairs of one user's speakers; the `:user` parameter is the user. */
        private const val USAGE_CTE = """
            WITH presence AS (
                SELECT v.transcript_id, v.speaker_id FROM transcript_voices v
                JOIN speakers sp ON sp.id = v.speaker_id WHERE sp.user_id = :user
                UNION
                SELECT p.transcript_id, p.speaker_id FROM transcript_segments p
                JOIN speakers sp ON sp.id = p.speaker_id WHERE sp.user_id = :user
            )
        """
    }
}
