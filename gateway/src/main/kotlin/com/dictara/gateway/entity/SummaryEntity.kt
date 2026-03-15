package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "summaries")
class SummaryEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "submission_id", nullable = false, unique = true) val submissionId: UUID,
    @Column var text: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
)
