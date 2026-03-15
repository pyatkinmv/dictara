package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "login_tokens")
class LoginTokenEntity(
    @Id val token: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    var user: UserEntity? = null,
    var confirmed: Boolean = false,
    var rejected: Boolean = false,
    @Column(name = "pending_username") val pendingUsername: String? = null,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
