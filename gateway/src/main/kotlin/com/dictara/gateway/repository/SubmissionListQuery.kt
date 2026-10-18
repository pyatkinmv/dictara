package com.dictara.gateway.repository

import com.dictara.gateway.service.ListSort
import com.dictara.gateway.service.ListStatuses
import com.dictara.gateway.service.TranscriptionListFilter
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Finds which submissions belong on a page of the transcription list, in the right order.
 *
 * One query serves every case (no filter at all, any mix of search, status, tag and speaker filters, any
 * sort): it has to reach into other tables (the title, the audio length, the tags, who speaks), which the
 * repository methods Spring derives from names cannot do. Only the ids come back; the entries themselves are
 * assembled from them afterwards in a few batched lookups.
 *
 * The SQL text is put together from fixed fragments. Everything that comes from the caller (the user id,
 * the search text, statuses and tag names, page and size) is a bound parameter, and the sort column is
 * picked from a fixed list, so nothing the client sends ends up inside the SQL.
 */
@Repository
class SubmissionListQuery(private val jdbc: NamedParameterJdbcTemplate) {

    /** The ids of one page in list order; [hasNext] tells whether another page follows. */
    data class Ids(val ids: List<UUID>, val hasNext: Boolean)

    /** A query and its parameters. */
    internal data class Built(val sql: String, val params: Map<String, Any?>)

    /**
     * The submissions of [userId] that match [filter], in the order of [filter].sort. Without [size] every
     * match is returned; otherwise the [page]th page of [size] (pages count from 0).
     */
    fun findIds(userId: UUID, filter: TranscriptionListFilter, page: Int? = null, size: Int? = null): Ids {
        val built = build(userId, filter, page, size)
        val ids = jdbc.query(built.sql, built.params) { rs, _ -> rs.getObject("id", UUID::class.java) }
        if (size == null) return Ids(ids, hasNext = false)
        // One row more than the page was asked for: its presence is the answer to "is there a next page".
        return Ids(ids.take(size), hasNext = ids.size > size)
    }

    internal fun build(userId: UUID, filter: TranscriptionListFilter, page: Int?, size: Int?): Built {
        val params = mutableMapOf<String, Any?>("user" to userId)
        val where = mutableListOf("s.user_id = :user")

        if (filter.statuses.isNotEmpty()) {
            where += "s.status IN (:statuses)"
            params["statuses"] = filter.statuses.flatMap { ListStatuses.stored[it].orEmpty() }
        }
        val search = filter.search?.trim().orEmpty()
        if (search.isNotEmpty()) {
            // The title as the list shows it. ILIKE does the case folding in the database, with its rules.
            where += "COALESCE(t.title, a.original_name) ILIKE :search ESCAPE '\\'"
            params["search"] = "%" + escapeLike(search) + "%"
        }
        if (filter.tags.isNotEmpty()) {
            where += """EXISTS (SELECT 1 FROM transcript_tags tt JOIN tags g ON g.id = tt.tag_id
                        WHERE tt.transcript_id = t.id AND g.user_id = :user AND g.name IN (:tags))"""
            params["tags"] = filter.tags.toList()
        }
        if (filter.speakers.isNotEmpty()) {
            // Same precedence as the header/Overview/list-count resolution: a phrase's own mark, else its
            // voice's confirmed speaker. An unconfirmed voice (no mark, no transcript_voices row) resolves to
            // no speaker_id at all, so the inner join drops it — it cannot match a name in the filter.
            where += """EXISTS (SELECT 1 FROM transcript_segments seg
                        LEFT JOIN transcript_voices tv ON tv.transcript_id = seg.transcript_id AND tv.voice = seg.voice
                        JOIN speakers sp ON sp.id = COALESCE(seg.speaker_id, tv.speaker_id)
                        WHERE seg.transcript_id = t.id AND sp.user_id = :user AND sp.name IN (:speakers))"""
            params["speakers"] = filter.speakers.toList()
        }

        val sql = StringBuilder(
            """
            SELECT s.id
            FROM submissions s
            JOIN audio_meta a ON a.id = s.audio_id
            LEFT JOIN transcripts t ON t.submission_id = s.id
            WHERE ${where.joinToString("\n  AND ")}
            ORDER BY ${orderBy(filter.sort)}
            """.trimIndent(),
        )
        if (size != null) {
            sql.append("\nLIMIT :limit OFFSET :offset")
            params["limit"] = size + 1
            params["offset"] = (page ?: 0).toLong() * size
        }
        return Built(sql.toString(), params)
    }

    private fun orderBy(sort: ListSort) = when (sort) {
        ListSort.NEWEST -> "s.created_at DESC, s.id DESC"
        ListSort.OLDEST -> "s.created_at ASC, s.id ASC"
        ListSort.LONGEST -> "a.duration_s DESC NULLS LAST, s.created_at DESC, s.id DESC"
        ListSort.RECORDED_NEWEST -> "COALESCE(a.recorded_at, s.created_at) DESC, s.created_at DESC, s.id DESC"
        ListSort.RECORDED_OLDEST -> "COALESCE(a.recorded_at, s.created_at) ASC, s.created_at ASC, s.id ASC"
        ListSort.NAME -> "lower(COALESCE(t.title, a.original_name)) ASC, s.created_at DESC, s.id DESC"
    }

    companion object {
        /** Makes [text] match only itself in a LIKE pattern: `\`, `%` and `_` lose their special meaning. */
        fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
