package com.dictara.gateway.entity

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("transcript_speakers")
class TranscriptSpeakerEntity(
    val transcriptId: UUID,
    val speakerId: UUID,
)
