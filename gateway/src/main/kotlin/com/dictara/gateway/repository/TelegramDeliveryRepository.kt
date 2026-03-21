package com.dictara.gateway.repository

import com.dictara.gateway.entity.TelegramDeliveryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TelegramDeliveryRepository : JpaRepository<TelegramDeliveryEntity, UUID> {

    @Query(value = """
        SELECT d.* FROM telegram_deliveries d
        JOIN submissions s ON s.id = d.job_id
        WHERE d.delivered_at IS NULL AND s.status IN ('done', 'failed')
    """, nativeQuery = true)
    fun findPendingDeliveries(): List<TelegramDeliveryEntity>

    @Modifying
    @Query(value = "UPDATE telegram_deliveries SET delivered_at = NOW() WHERE job_id = :jobId AND delivered_at IS NULL", nativeQuery = true)
    fun claimDelivery(@Param("jobId") jobId: UUID): Int
}
