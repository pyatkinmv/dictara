package com.dictara.gateway.entity

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("transcripts")
class TranscriptEntity(
    @Id val id: UUID? = null,
    val submissionId: UUID,
    var segments: JsonNode? = null,
    var audioDurationS: Double? = null,
    val createdAt: Instant = Instant.now(),
)
