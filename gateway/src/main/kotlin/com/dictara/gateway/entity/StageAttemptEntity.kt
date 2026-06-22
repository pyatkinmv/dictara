package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("stage_attempts")
class StageAttemptEntity(
    @Id val id: UUID? = null,
    val submissionId: UUID,
    val stage: String,
    val attemptNum: Int,
    var status: String,
    var externalJobId: String? = null,
    var startedAt: Instant? = null,
    var finishedAt: Instant? = null,
    var error: String? = null,
)
