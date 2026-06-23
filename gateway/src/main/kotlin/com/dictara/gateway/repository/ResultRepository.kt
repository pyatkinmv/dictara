package com.dictara.gateway.repository

import com.dictara.gateway.entity.SummaryEntity
import com.dictara.gateway.entity.TranscriptEntity
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface TranscriptRepository : CrudRepository<TranscriptEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): TranscriptEntity?
}

interface SummaryRepository : CrudRepository<SummaryEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): SummaryEntity?
}
