package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("users")
class UserEntity(
    @Id val id: UUID? = null,
    var displayName: String? = null,
    val createdAt: Instant = Instant.now(),
    val plan: String = "free",
)
