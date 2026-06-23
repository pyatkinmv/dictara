package com.dictara.gateway.repository

import com.dictara.gateway.entity.TagEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface TagRepository : CrudRepository<TagEntity, UUID> {
    fun findByUserIdAndName(userId: UUID, name: String): TagEntity?

    @Query("SELECT t.* FROM tags t JOIN submission_tags st ON st.tag_id = t.id WHERE st.submission_id = :submissionId ORDER BY t.name")
    fun findBySubmissionId(submissionId: UUID): List<TagEntity>
}
