package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "audio_meta")
class AudioMetaEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    val user: UserEntity,
    @Column(name = "original_name", nullable = false) val originalName: String,
    @Column(name = "content_type", nullable = false) val contentType: String,
    @Column(name = "size_bytes", nullable = false) val sizeBytes: Long,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
