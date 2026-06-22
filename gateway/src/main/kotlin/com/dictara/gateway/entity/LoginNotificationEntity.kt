package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("login_notifications")
class LoginNotificationEntity(
    @Id val id: Long? = null,
    @Column("token") val tokenId: UUID,
    val chatId: String,
    var sent: Boolean = false,
    val createdAt: Instant = Instant.now(),
)
