package com.dictara.gateway.repository

import com.dictara.gateway.entity.StageAttemptEntity
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import java.util.UUID

interface StageAttemptRepository : JpaRepository<StageAttemptEntity, UUID> {

    fun findBySubmissionIdAndStageOrderByAttemptNumDesc(
        submissionId: UUID, stage: String,
    ): List<StageAttemptEntity>

    fun countBySubmissionIdAndStage(submissionId: UUID, stage: String): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(value = [QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")])
    @Query("""
        SELECT a FROM StageAttemptEntity a
        WHERE a.status = 'processing'
          AND a.externalJobId IS NOT NULL
        ORDER BY a.startedAt
    """)
    fun findInFlightForUpdate(): List<StageAttemptEntity>
}
