package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("summaries")
class SummaryEntity(
    @Id val id: UUID? = null,
    val submissionId: UUID,
    var text: String? = null,
    val createdAt: Instant = Instant.now(),
)
