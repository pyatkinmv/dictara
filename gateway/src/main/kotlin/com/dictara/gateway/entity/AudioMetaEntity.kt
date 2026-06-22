package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("audio_meta")
class AudioMetaEntity(
    @Id @get:JvmName("getIdValue") val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant = Instant.now(),
    val storageUri: String? = null,
    val contentHash: String,
    @Transient val _isNew: Boolean = false,
) : Persistable<UUID> {
    override fun getId(): UUID = id
    override fun isNew(): Boolean = _isNew
}
