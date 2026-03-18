package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionTagEntity
import com.dictara.gateway.entity.SubmissionTagId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface SubmissionTagRepository : JpaRepository<SubmissionTagEntity, SubmissionTagId> {
    fun findBySubmissionId(submissionId: UUID): List<SubmissionTagEntity>
    fun findBySubmissionIdIn(submissionIds: Collection<UUID>): List<SubmissionTagEntity>
    @Transactional
    fun deleteBySubmissionIdAndTag(submissionId: UUID, tag: String)
    fun existsBySubmissionIdAndTag(submissionId: UUID, tag: String): Boolean
}
