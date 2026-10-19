package com.dictara.gateway.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** What the tag library screen shows about the tags: how much each is used and where. */
@Repository
class TagLibraryQueries(private val jdbc: NamedParameterJdbcTemplate) {

    /** A tag with its usage: [transcriptionCount] transcriptions carry it, [samples] are the latest finished ones of them. */
    data class Row(
        val id: UUID,
        val name: String,
        val description: String?,
        val transcriptionCount: Int,
        val samples: List<LibrarySample>,
    )

    /** The user's tags in creation order (ties by name), each with its [sampleSize] most recent finished transcriptions. */
    fun findLibrary(userId: UUID, sampleSize: Int): List<Row> {
        val samples = jdbc.query(
            """
            SELECT x.tag_id, x.job_id, x.title
            FROM (
                SELECT tt.tag_id,
                       s.id AS job_id,
                       COALESCE(t.title, a.original_name) AS title,
                       row_number() OVER (PARTITION BY tt.tag_id ORDER BY s.created_at DESC, s.id DESC) AS rn
                FROM transcript_tags tt
                JOIN tags g ON g.id = tt.tag_id
                JOIN transcripts t ON t.id = tt.transcript_id
                JOIN submissions s ON s.id = t.submission_id
                JOIN audio_meta a ON a.id = s.audio_id
                WHERE g.user_id = :user AND s.status = 'done'
            ) x
            WHERE x.rn <= :size
            ORDER BY x.tag_id, x.rn
            """.trimIndent(),
            mapOf("user" to userId, "size" to sampleSize),
        ) { rs, _ ->
            rs.getObject("tag_id", UUID::class.java) to LibrarySample(rs.getObject("job_id", UUID::class.java), rs.getString("title"))
        }.groupBy({ it.first }, { it.second })

        return jdbc.query(
            """
            SELECT g.id, g.name, g.description,
                   (SELECT count(*) FROM transcript_tags tt WHERE tt.tag_id = g.id) AS transcription_count
            FROM tags g
            WHERE g.user_id = :user
            ORDER BY g.created_at ASC, g.name ASC
            """.trimIndent(),
            mapOf("user" to userId),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            Row(id, rs.getString("name"), rs.getString("description"), rs.getInt("transcription_count"), samples[id] ?: emptyList())
        }
    }

    /** How many distinct transcriptions of the user carry at least one tag. */
    fun countTaggedTranscriptions(userId: UUID): Int = jdbc.queryForObject(
        """
        SELECT count(DISTINCT tt.transcript_id)
        FROM transcript_tags tt JOIN tags g ON g.id = tt.tag_id
        WHERE g.user_id = :user
        """.trimIndent(),
        mapOf("user" to userId),
        Int::class.java,
    ) ?: 0
}
