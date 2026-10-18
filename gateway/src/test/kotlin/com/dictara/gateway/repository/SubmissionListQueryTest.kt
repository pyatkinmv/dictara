package com.dictara.gateway.repository

import com.dictara.gateway.service.ListSort
import com.dictara.gateway.service.TranscriptionListFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.mockito.Mockito.mock
import java.util.UUID

/** The SQL the list is asked with: which pieces appear when, and that nothing the caller sends is in the text. */
class SubmissionListQueryTest {

    private val query = SubmissionListQuery(mock(NamedParameterJdbcTemplate::class.java))
    private val user = UUID.randomUUID()

    private fun build(filter: TranscriptionListFilter = TranscriptionListFilter(), page: Int? = null, size: Int? = null) =
        query.build(user, filter, page, size)

    @Test
    fun `without a filter the list is the user's, newest first, and nothing else`() {
        val built = build()

        assertThat(built.sql).contains("WHERE s.user_id = :user")
        assertThat(built.sql).contains("ORDER BY s.created_at DESC, s.id DESC")
        assertThat(built.sql).doesNotContain("s.status IN").doesNotContain("ILIKE").doesNotContain("transcript_tags").doesNotContain("LIMIT")
        assertThat(built.params).containsOnlyKeys("user")
        assertThat(built.params["user"]).isEqualTo(user)
    }

    @Test
    fun `each filter adds its condition and parameter`() {
        val built = build(TranscriptionListFilter(
            search = "review", statuses = setOf("done"), tags = setOf("meeting"), speakers = setOf("Maria"),
        ))

        assertThat(built.sql).contains("s.status IN (:statuses)").contains("ILIKE :search ESCAPE")
            .contains("g.name IN (:tags)").contains("sp.name IN (:speakers)")
        assertThat(built.params).containsOnlyKeys("user", "statuses", "search", "tags", "speakers")
        assertThat(built.params["tags"]).isEqualTo(listOf("meeting"))
        assertThat(built.params["speakers"]).isEqualTo(listOf("Maria"))
    }

    @Test
    fun `no speaker filter means no speaker condition`() {
        assertThat(build().sql).doesNotContain("transcript_segments").doesNotContain("sp.name")
    }

    @Test
    fun `processing stands for processing and summarizing`() {
        assertThat(build(TranscriptionListFilter(statuses = setOf("processing"))).params["statuses"])
            .isEqualTo(listOf("processing", "summarizing"))
        assertThat(build(TranscriptionListFilter(statuses = setOf("pending", "failed"))).params["statuses"])
            .isEqualTo(listOf("pending", "failed"))
    }

    @Test
    fun `a blank search is no search, and the text is trimmed`() {
        assertThat(build(TranscriptionListFilter(search = null)).sql).doesNotContain("ILIKE")
        assertThat(build(TranscriptionListFilter(search = "")).sql).doesNotContain("ILIKE")
        assertThat(build(TranscriptionListFilter(search = "   ")).sql).doesNotContain("ILIKE")
        assertThat(build(TranscriptionListFilter(search = "  call ")).params["search"]).isEqualTo("%call%")
    }

    @Test
    fun `nothing from the caller is written into the SQL text`() {
        val hostile = "x'; DROP TABLE submissions; --"
        val built = build(TranscriptionListFilter(search = hostile, tags = setOf(hostile), speakers = setOf(hostile)), page = 2, size = 30)

        assertThat(built.sql).doesNotContain("DROP").doesNotContain(hostile).doesNotContain("x'")
        assertThat(built.params["search"] as String).contains("DROP TABLE") // it travels as a value
    }

    @Test
    fun `every sort has its own order, ties broken by time and id`() {
        fun order(sort: ListSort) = build(TranscriptionListFilter(sort = sort)).sql.substringAfter("ORDER BY ")

        assertThat(order(ListSort.NEWEST)).isEqualTo("s.created_at DESC, s.id DESC")
        assertThat(order(ListSort.OLDEST)).isEqualTo("s.created_at ASC, s.id ASC")
        assertThat(order(ListSort.LONGEST)).isEqualTo("a.duration_s DESC NULLS LAST, s.created_at DESC, s.id DESC")
        assertThat(order(ListSort.RECORDED_NEWEST)).isEqualTo("COALESCE(a.recorded_at, s.created_at) DESC, s.created_at DESC, s.id DESC")
        assertThat(order(ListSort.RECORDED_OLDEST)).isEqualTo("COALESCE(a.recorded_at, s.created_at) ASC, s.created_at ASC, s.id ASC")
        assertThat(order(ListSort.NAME)).isEqualTo("lower(COALESCE(t.title, a.original_name)) ASC, s.created_at DESC, s.id DESC")
    }

    @Test
    fun `a page asks for one row more than its size, from the right offset`() {
        val built = build(page = 2, size = 30)

        assertThat(built.sql).endsWith("LIMIT :limit OFFSET :offset")
        assertThat(built.params["limit"]).isEqualTo(31)
        assertThat(built.params["offset"]).isEqualTo(60L)
    }

    @Test
    fun `a huge page number does not overflow the offset`() {
        assertThat(build(page = Int.MAX_VALUE, size = 500).params["offset"]).isEqualTo(Int.MAX_VALUE.toLong() * 500)
    }

    @Test
    fun `wildcards in the search text are escaped so they match themselves`() {
        assertThat(SubmissionListQuery.escapeLike("100%")).isEqualTo("100\\%")
        assertThat(SubmissionListQuery.escapeLike("a_b")).isEqualTo("a\\_b")
        assertThat(SubmissionListQuery.escapeLike("back\\slash")).isEqualTo("back\\\\slash")
        assertThat(SubmissionListQuery.escapeLike("plain")).isEqualTo("plain")
        assertThat(build(TranscriptionListFilter(search = "50%_off")).params["search"]).isEqualTo("%50\\%\\_off%")
    }
}
