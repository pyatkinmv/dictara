package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("telegram_deliveries")
class TelegramDeliveryEntity(
    @Id val jobId: UUID,
    val chatId: Long,
    val telegramMessageId: Long? = null,
    var deliveredAt: Instant? = null,
    var claimedAt: Instant? = null,
    var retryAfterTs: Instant? = null,
    var attemptCount: Int = 0,
    @Transient val _isNew: Boolean = false,
) : Persistable<UUID> {
    override fun getId(): UUID = jobId
    override fun isNew(): Boolean = _isNew
}
