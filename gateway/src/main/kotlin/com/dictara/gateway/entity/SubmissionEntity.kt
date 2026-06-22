package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("submissions")
class SubmissionEntity(
    @Id val id: UUID? = null,
    val userId: UUID,
    val audioId: UUID,
    val model: String = "fast",
    val language: String = "auto",
    val diarize: Boolean = false,
    val numSpeakers: Int? = null,
    val summaryMode: String = "off",
    val source: String = "web",
    var status: String = "pending",
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)
