package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("audio_meta")
class AudioMetaEntity(
    @Id val id: UUID? = null,
    val userId: UUID,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant = Instant.now(),
    val storageUri: String? = null,
    val contentHash: String,
)
