package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionEntity
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SubmissionRepository : JpaRepository<SubmissionEntity, UUID> {

    fun findByUser_IdOrderByCreatedAtDesc(userId: UUID): List<SubmissionEntity>

    fun findByUser_IdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<SubmissionEntity>

    fun existsByStatus(status: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(value = [QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")])
    @Query("SELECT s FROM SubmissionEntity s JOIN FETCH s.audio JOIN FETCH s.user WHERE s.status = 'pending' ORDER BY s.createdAt LIMIT 1")
    fun findNextPendingForUpdate(): SubmissionEntity?

    @Query("SELECT COUNT(s) FROM SubmissionEntity s WHERE s.status = 'pending' AND s.createdAt < :createdAt")
    fun countPendingSubmissionsBefore(createdAt: Instant): Long

    @Query("SELECT COUNT(s) FROM SubmissionEntity s WHERE s.status = :status")
    fun countByStatus(@Param("status") status: String): Long

    @Query("""
        SELECT s FROM SubmissionEntity s
        WHERE s.audio.user.id = :userId
        AND s.audio.contentHash = :contentHash
        AND s.model = :model
        AND s.language = :language
        AND s.diarize = :diarize
        AND COALESCE(s.numSpeakers, 0) = COALESCE(:numSpeakers, 0)
        AND s.summaryMode = :summaryMode
        AND s.status <> 'failed'
        ORDER BY s.createdAt DESC
        LIMIT 1
    """)
    fun findDuplicate(
        @Param("userId") userId: UUID,
        @Param("contentHash") contentHash: String,
        @Param("model") model: String,
        @Param("language") language: String,
        @Param("diarize") diarize: Boolean,
        @Param("numSpeakers") numSpeakers: Int?,
        @Param("summaryMode") summaryMode: String,
    ): SubmissionEntity?
}
