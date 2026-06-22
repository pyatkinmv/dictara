package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionTagEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface SubmissionTagRepository : Repository<SubmissionTagEntity, UUID> {

    @Query("SELECT * FROM submission_tags WHERE submission_id = :submissionId")
    fun findBySubmissionId(submissionId: UUID): List<SubmissionTagEntity>

    @Query("SELECT * FROM submission_tags WHERE submission_id IN (:submissionIds)")
    fun findBySubmissionIdIn(submissionIds: Collection<UUID>): List<SubmissionTagEntity>

    @Query("INSERT INTO submission_tags(submission_id, tag) VALUES (:submissionId, :tag)")
    fun insert(submissionId: UUID, tag: String)

    @Query("DELETE FROM submission_tags WHERE submission_id = :submissionId AND tag = :tag")
    fun deleteBySubmissionIdAndTag(submissionId: UUID, tag: String)

    @Query("SELECT EXISTS(SELECT 1 FROM submission_tags WHERE submission_id = :submissionId AND tag = :tag)")
    fun existsBySubmissionIdAndTag(submissionId: UUID, tag: String): Boolean
}
