package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "stage_attempts")
class StageAttemptEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID) val id: UUID? = null,
    @Column(name = "submission_id", nullable = false) val submissionId: UUID,
    @Column(nullable = false) val stage: String,
    @Column(name = "attempt_num", nullable = false) val attemptNum: Int,
    @Column(nullable = false) var status: String,
    @Column(name = "external_job_id") var externalJobId: String? = null,
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "finished_at") var finishedAt: Instant? = null,
    @Column var error: String? = null,
)
