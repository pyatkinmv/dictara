package com.dictara.gateway.repository

import com.dictara.gateway.entity.TagEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface TagRepository : CrudRepository<TagEntity, UUID> {
    fun findByUserIdAndName(userId: UUID, name: String): TagEntity?

    @Query("SELECT t.* FROM tags t JOIN transcript_tags tt ON tt.tag_id = t.id WHERE tt.transcript_id = :transcriptId ORDER BY t.name")
    fun findByTranscriptId(transcriptId: UUID): List<TagEntity>
}
