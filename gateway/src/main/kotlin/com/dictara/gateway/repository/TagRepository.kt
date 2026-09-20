package com.dictara.gateway.repository

import com.dictara.gateway.entity.TagEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface TagRepository : CrudRepository<TagEntity, UUID> {
    fun findByUserIdAndName(userId: UUID, name: String): TagEntity?

    /** The user's library in creation order (ties by name, so the order is always the same). */
    fun findByUserIdOrderByCreatedAtAscNameAsc(userId: UUID): List<TagEntity>

    /** Renames and/or re-describes one tag; the caller has checked that it exists and whose it is. */
    @Modifying
    @Query("UPDATE tags SET name = :name, description = :description, updated_at = now() WHERE id = :id")
    fun update(id: UUID, name: String, description: String?)

    @Query("SELECT count(*) FROM transcript_tags WHERE tag_id = :tagId")
    fun countTranscriptions(tagId: UUID): Int

    @Query("SELECT t.* FROM tags t JOIN transcript_tags tt ON tt.tag_id = t.id WHERE tt.transcript_id = :transcriptId ORDER BY t.name")
    fun findByTranscriptId(transcriptId: UUID): List<TagEntity>
}
