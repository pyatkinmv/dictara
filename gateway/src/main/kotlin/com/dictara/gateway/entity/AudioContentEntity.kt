package com.dictara.gateway.entity

import jakarta.persistence.*
import java.util.UUID

@Entity @Table(name = "audio_content")
class AudioContentEntity(
    @Id @Column(name = "audio_id") val audioId: UUID,
    @Column(nullable = false, columnDefinition = "bytea") val data: ByteArray,
)
