package com.dictara.gateway.repository

import com.dictara.gateway.entity.StageAttemptEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface StageAttemptRepository : CrudRepository<StageAttemptEntity, UUID> {

    fun findBySubmissionIdAndStageOrderByAttemptNumDesc(
        submissionId: UUID, stage: String,
    ): List<StageAttemptEntity>

    fun countBySubmissionIdAndStage(submissionId: UUID, stage: String): Long

    @Query("""
        SELECT * FROM stage_attempts
        WHERE status = 'processing'
          AND external_job_id IS NOT NULL
        ORDER BY started_at
        FOR UPDATE
    """)
    fun findInFlightForUpdate(): List<StageAttemptEntity>
}
