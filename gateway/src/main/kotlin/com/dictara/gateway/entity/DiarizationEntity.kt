package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("diarizations")
class DiarizationEntity(
    @Id val id: UUID? = null,
    val submissionId: UUID,
    var segments: String? = null,
    var formattedText: String? = null,
    val createdAt: Instant = Instant.now(),
)
