package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "login_notifications")
class LoginNotificationEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "token")
    val loginToken: LoginTokenEntity,
    @Column(name = "chat_id", nullable = false)
    val chatId: String,
    var sent: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
