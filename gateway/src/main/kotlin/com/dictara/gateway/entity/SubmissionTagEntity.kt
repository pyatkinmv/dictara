package com.dictara.gateway.entity

import jakarta.persistence.*
import java.io.Serializable
import java.util.UUID

@Entity
@Table(name = "submission_tags")
@IdClass(SubmissionTagId::class)
class SubmissionTagEntity(
    @Id @Column(name = "submission_id") val submissionId: UUID,
    @Id @Column(name = "tag") val tag: String,
)

data class SubmissionTagId(
    val submissionId: UUID = UUID.randomUUID(),
    val tag: String = "",
) : Serializable
