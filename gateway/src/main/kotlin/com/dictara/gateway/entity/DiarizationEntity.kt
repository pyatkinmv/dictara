package com.dictara.gateway.entity

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("diarizations")
class DiarizationEntity(
    @Id val id: UUID? = null,
    val submissionId: UUID,
    var segments: JsonNode? = null,
    var formattedText: String? = null,
    val createdAt: Instant = Instant.now(),
)
