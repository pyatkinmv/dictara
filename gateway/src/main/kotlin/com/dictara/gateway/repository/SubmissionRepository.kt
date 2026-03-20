package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionEntity
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import java.time.Instant
import java.util.UUID

interface SubmissionRepository : JpaRepository<SubmissionEntity, UUID> {

    fun findByUser_IdOrderByCreatedAtDesc(userId: UUID): List<SubmissionEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(value = [QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")])
    @Query("SELECT s FROM SubmissionEntity s JOIN FETCH s.audio JOIN FETCH s.user WHERE s.status = 'pending' ORDER BY s.createdAt")
    fun findPendingForUpdate(): List<SubmissionEntity>

    @Query("SELECT COUNT(s) FROM SubmissionEntity s WHERE s.status IN ('pending', 'processing') AND s.createdAt < :createdAt")
    fun countActiveSubmissionsBefore(createdAt: Instant): Long
}
