package com.dictara.gateway.entity

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("submission_speakers")
class SubmissionSpeakerEntity(
    val submissionId: UUID,
    val speakerId: UUID,
)
