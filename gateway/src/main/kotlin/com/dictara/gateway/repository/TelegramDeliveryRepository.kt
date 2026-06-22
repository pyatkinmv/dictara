package com.dictara.gateway.repository

import com.dictara.gateway.entity.TelegramDeliveryEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.UUID

interface TelegramDeliveryRepository : CrudRepository<TelegramDeliveryEntity, UUID> {

    @Query("""
        SELECT d.* FROM telegram_deliveries d
        JOIN submissions s ON s.id = d.job_id
        WHERE d.delivered_at IS NULL
          AND d.claimed_at IS NULL
          AND d.attempt_count < 10
          AND (d.retry_after_ts IS NULL OR d.retry_after_ts < NOW())
          AND s.status IN ('done', 'failed')
    """)
    fun findPendingDeliveries(): List<TelegramDeliveryEntity>

    @Query("""
        UPDATE telegram_deliveries
        SET claimed_at = NOW(), attempt_count = attempt_count + 1
        WHERE job_id = :jobId AND claimed_at IS NULL AND delivered_at IS NULL
    """)
    fun claimDelivery(jobId: UUID): Int

    @Query("UPDATE telegram_deliveries SET delivered_at = NOW() WHERE job_id = :jobId")
    fun confirmDelivered(jobId: UUID): Int

    @Query("""
        UPDATE telegram_deliveries
        SET claimed_at = NULL, retry_after_ts = :retryAfterTs
        WHERE job_id = :jobId
    """)
    fun scheduleRetry(jobId: UUID, retryAfterTs: Instant): Int

    @Query("SELECT COUNT(*) FROM telegram_deliveries WHERE delivered_at IS NULL AND attempt_count < 10")
    fun countUndelivered(): Long

    @Query("SELECT COUNT(*) FROM telegram_deliveries WHERE delivered_at IS NULL AND attempt_count >= 10")
    fun countExhausted(): Long
}
