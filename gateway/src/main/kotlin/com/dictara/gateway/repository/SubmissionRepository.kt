package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.UUID

interface SubmissionRepository : CrudRepository<SubmissionEntity, UUID> {

    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<SubmissionEntity>

    fun findByUserIdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<SubmissionEntity>

    fun existsByStatus(status: String): Boolean

    @Query("""
        SELECT * FROM submissions
        WHERE status = 'pending'
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE NOWAIT
    """)
    fun findNextPendingForUpdate(): SubmissionEntity?

    @Query("SELECT COUNT(*) FROM submissions WHERE status = 'pending' AND created_at < :createdAt")
    fun countPendingSubmissionsBefore(createdAt: Instant): Long

    @Query("SELECT COUNT(*) FROM submissions WHERE status = :status")
    fun countByStatus(status: String): Long

    @Query("""
        SELECT s.* FROM submissions s
        JOIN audio_meta a ON s.audio_id = a.id
        WHERE a.user_id = :userId
          AND a.content_hash = :contentHash
          AND s.model = :model
          AND s.language_hint = :languageHint
          AND s.diarize = :diarize
          AND COALESCE(s.num_speakers, 0) = COALESCE(:numSpeakers, 0)
          AND s.summary_mode = :summaryMode
          AND s.status <> 'failed'
        ORDER BY s.created_at DESC
        LIMIT 1
    """)
    fun findDuplicate(
        userId: UUID,
        contentHash: String,
        model: String,
        languageHint: String,
        diarize: Boolean,
        numSpeakers: Int?,
        summaryMode: String,
    ): SubmissionEntity?

    @Query("""
        SELECT COUNT(*) FROM submissions
        WHERE user_id = :userId
          AND created_at >= date_trunc('month', NOW() AT TIME ZONE 'UTC')
          AND status <> 'failed'
    """)
    fun countCurrentMonthSubmissions(userId: UUID): Long
}
