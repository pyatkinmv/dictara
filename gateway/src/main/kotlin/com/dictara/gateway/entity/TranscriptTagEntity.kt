package com.dictara.gateway.entity

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("transcript_tags")
class TranscriptTagEntity(
    val transcriptId: UUID,
    val tagId: UUID,
)
