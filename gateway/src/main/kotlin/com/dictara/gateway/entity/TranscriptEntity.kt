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
    // TODO(drop-json-segments): the phrases now live in transcript_segments (one row each) and every reader uses
    // those. This JSON cell is still written next to them for one release so that the previous version of the
    // gateway can be rolled back to. After that release drop the column, this field and the writes to it.
    var segments: JsonNode? = null,
    var detectedLanguage: String? = null,
    var title: String? = null,
    val createdAt: Instant = Instant.now(),
)
