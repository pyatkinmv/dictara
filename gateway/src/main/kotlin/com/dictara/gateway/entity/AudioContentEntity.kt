package com.dictara.gateway.entity

import jakarta.persistence.*
import java.util.UUID

@Deprecated("audio_content table has been migrated to GCS — all rows deleted, table retained for schema history only")
@Entity @Table(name = "audio_content")
class AudioContentEntity(
    @Id @Column(name = "audio_id") val audioId: UUID,
    @Column(nullable = false, columnDefinition = "bytea") val data: ByteArray,
)
