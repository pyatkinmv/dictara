package com.dictara.gateway.repository

import com.dictara.gateway.entity.TelegramDeliveryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TelegramDeliveryRepository : JpaRepository<TelegramDeliveryEntity, UUID> {

    @Query(value = """
        SELECT d.* FROM telegram_deliveries d
        JOIN submissions s ON s.id = d.job_id
        WHERE d.delivered_at IS NULL
          AND d.claimed_at IS NULL
          AND d.attempt_count < 10
          AND (d.retry_after_ts IS NULL OR d.retry_after_ts < NOW())
          AND s.status IN ('done', 'failed')
    """, nativeQuery = true)
    fun findPendingDeliveries(): List<TelegramDeliveryEntity>

    @Modifying
    @Query(value = """
        UPDATE telegram_deliveries
        SET claimed_at = NOW(), attempt_count = attempt_count + 1
        WHERE job_id = :jobId AND claimed_at IS NULL AND delivered_at IS NULL
    """, nativeQuery = true)
    fun claimDelivery(@Param("jobId") jobId: UUID): Int

    @Modifying
    @Query(value = "UPDATE telegram_deliveries SET delivered_at = NOW() WHERE job_id = :jobId", nativeQuery = true)
    fun confirmDelivered(@Param("jobId") jobId: UUID)

    @Modifying
    @Query(value = """
        UPDATE telegram_deliveries
        SET claimed_at = NULL, retry_after_ts = :retryAfterTs
        WHERE job_id = :jobId
    """, nativeQuery = true)
    fun scheduleRetry(@Param("jobId") jobId: UUID, @Param("retryAfterTs") retryAfterTs: Instant)

    @Query(value = "SELECT COUNT(*) FROM telegram_deliveries WHERE delivered_at IS NULL AND attempt_count < 10", nativeQuery = true)
    fun countUndelivered(): Long

    @Query(value = "SELECT COUNT(*) FROM telegram_deliveries WHERE delivered_at IS NULL AND attempt_count >= 10", nativeQuery = true)
    fun countExhausted(): Long
}
