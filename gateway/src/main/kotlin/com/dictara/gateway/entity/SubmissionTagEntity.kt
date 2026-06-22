package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.io.Serializable
import java.util.UUID

@Table("submission_tags")
class SubmissionTagEntity(
    @Id val submissionId: UUID,
    @Id val tag: String,
)

data class SubmissionTagId(
    val submissionId: UUID = UUID.randomUUID(),
    val tag: String = "",
) : Serializable
