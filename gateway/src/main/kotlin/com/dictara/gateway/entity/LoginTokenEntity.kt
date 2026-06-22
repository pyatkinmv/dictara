package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("login_tokens")
class LoginTokenEntity(
    @Id val token: UUID,
    var userId: UUID? = null,
    var confirmed: Boolean = false,
    var rejected: Boolean = false,
    val pendingUsername: String? = null,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
) : Persistable<UUID> {
    @Transient var _isNew: Boolean = false
    override fun getId(): UUID = token
    override fun isNew(): Boolean = _isNew
}
