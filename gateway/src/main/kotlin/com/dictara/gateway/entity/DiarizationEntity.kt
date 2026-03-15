package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "diarizations")
class DiarizationEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "submission_id", nullable = false, unique = true) val submissionId: UUID,
    @Column(columnDefinition = "jsonb") var segments: String? = null,
    @Column(name = "formatted_text") var formattedText: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
