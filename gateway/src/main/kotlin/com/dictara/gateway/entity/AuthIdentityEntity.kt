package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("auth_identities")
class AuthIdentityEntity(
    @Id val id: UUID? = null,
    val userId: UUID,
    val provider: String,
    val providerUid: String,
    val credentials: String? = null,
    val metadata: String? = null,
    val createdAt: Instant = Instant.now(),
)
