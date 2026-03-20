package com.dictara.gateway.repository

import com.dictara.gateway.entity.TelegramDeliveryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TelegramDeliveryRepository : JpaRepository<TelegramDeliveryEntity, UUID> {

    @Query(value = """
        SELECT d.* FROM telegram_deliveries d
        JOIN submissions s ON s.id = d.job_id
        WHERE d.delivered_at IS NULL AND s.status IN ('done', 'failed')
    """, nativeQuery = true)
    fun findPendingDeliveries(): List<TelegramDeliveryEntity>
}
