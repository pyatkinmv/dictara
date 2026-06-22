package com.dictara.gateway.entity

import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("submission_tags")
class SubmissionTagEntity(
    val submissionId: UUID,
    val tag: String,
)
