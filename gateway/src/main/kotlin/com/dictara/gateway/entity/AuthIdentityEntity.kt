package com.dictara.gateway.entity

import com.fasterxml.jackson.databind.JsonNode
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
    val credentials: JsonNode? = null,
    val metadata: JsonNode? = null,
    val createdAt: Instant = Instant.now(),
)
