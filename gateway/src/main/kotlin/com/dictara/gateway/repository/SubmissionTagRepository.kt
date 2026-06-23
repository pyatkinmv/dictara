package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionTagEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface SubmissionTagRepository : Repository<SubmissionTagEntity, UUID> {

    @Query("SELECT * FROM submission_tags WHERE submission_id = :submissionId")
    fun findBySubmissionId(submissionId: UUID): List<SubmissionTagEntity>

    @Query("SELECT * FROM submission_tags WHERE submission_id IN (:submissionIds)")
    fun findBySubmissionIdIn(submissionIds: Collection<UUID>): List<SubmissionTagEntity>

    @Modifying
    @Query("INSERT INTO submission_tags(submission_id, tag_id) VALUES (:submissionId, :tagId)")
    fun insert(submissionId: UUID, tagId: UUID)

    @Modifying
    @Query("DELETE FROM submission_tags WHERE submission_id = :submissionId AND tag_id = :tagId")
    fun deleteBySubmissionIdAndTagId(submissionId: UUID, tagId: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM submission_tags WHERE submission_id = :submissionId AND tag_id = :tagId)")
    fun existsBySubmissionIdAndTagId(submissionId: UUID, tagId: UUID): Boolean
}
