package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionTagEntity
import com.dictara.gateway.entity.SubmissionTagId
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface SubmissionTagRepository : CrudRepository<SubmissionTagEntity, SubmissionTagId> {
    fun findBySubmissionId(submissionId: UUID): List<SubmissionTagEntity>
    fun findBySubmissionIdIn(submissionIds: Collection<UUID>): List<SubmissionTagEntity>
    fun deleteBySubmissionIdAndTag(submissionId: UUID, tag: String)
    fun existsBySubmissionIdAndTag(submissionId: UUID, tag: String): Boolean
}
