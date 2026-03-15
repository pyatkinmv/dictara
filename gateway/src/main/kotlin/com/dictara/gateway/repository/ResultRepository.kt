package com.dictara.gateway.repository

import com.dictara.gateway.entity.DiarizationEntity
import com.dictara.gateway.entity.SummaryEntity
import com.dictara.gateway.entity.TranscriptEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TranscriptRepository : JpaRepository<TranscriptEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): TranscriptEntity?
}

interface DiarizationRepository : JpaRepository<DiarizationEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): DiarizationEntity?
}

interface SummaryRepository : JpaRepository<SummaryEntity, UUID> {
    fun findBySubmissionId(submissionId: UUID): SummaryEntity?
}
