package com.dictara.gateway.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "telegram_deliveries")
class TelegramDeliveryEntity(
    @Id val jobId: UUID,
    @Column(nullable = false) val chatId: Long,
    @Column val telegramMessageId: Long? = null,
    @Column var deliveredAt: Instant? = null,
    @Column var claimedAt: Instant? = null,
    @Column var retryAfterTs: Instant? = null,
    @Column var attemptCount: Int = 0,
)
