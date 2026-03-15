package com.dictara.gateway.entity

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "transcripts")
class TranscriptEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "submission_id", nullable = false, unique = true) val submissionId: UUID,
    @Column(columnDefinition = "jsonb") @JdbcTypeCode(SqlTypes.JSON) var segments: String? = null,
    @Column(name = "formatted_text") var formattedText: String? = null,
    @Column(name = "audio_duration_s") var audioDurationS: Double? = null,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
