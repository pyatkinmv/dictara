package com.dictara.gateway.repository

import com.dictara.gateway.entity.SummaryEntity
import com.dictara.gateway.entity.TranscriptEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface TranscriptRepository : CrudRepository<TranscriptEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE submission_id IN (:submissionIds)")
    fun findBySubmissionIdIn(submissionIds: Collection<UUID>): List<TranscriptEntity>

    @Modifying
    @Query("UPDATE transcripts SET title = :title WHERE id = :id")
    fun updateTitle(id: UUID, title: String?)
}

interface SummaryRepository : CrudRepository<SummaryEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): SummaryEntity?
}
