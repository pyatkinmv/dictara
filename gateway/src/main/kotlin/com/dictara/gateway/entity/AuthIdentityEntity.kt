package com.dictara.gateway.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "auth_identities")
class AuthIdentityEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    val user: UserEntity,
    @Column(nullable = false) val provider: String,
    @Column(name = "provider_uid", nullable = false) val providerUid: String,
    @Column(columnDefinition = "jsonb") @JdbcTypeCode(SqlTypes.JSON) val credentials: String? = null,
    @Column(columnDefinition = "jsonb") @JdbcTypeCode(SqlTypes.JSON) val metadata: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
