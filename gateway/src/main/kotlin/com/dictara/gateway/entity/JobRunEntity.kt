package com.dictara.gateway.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "job_runs")
class JobRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "job_name", nullable = false) val jobName: String,
    @Column(nullable = false) val status: String = "running",
    @Column(name = "started_at", nullable = false, updatable = false) val startedAt: Instant = Instant.now(),
    @Column(name = "finished_at") val finishedAt: Instant? = null,
    @Column(name = "rows_affected") val rowsAffected: Int? = null,
    val error: String? = null,
) {
    fun completed(rowsAffected: Int? = null) = JobRunEntity(
        id = id, jobName = jobName, status = "completed",
        startedAt = startedAt, finishedAt = Instant.now(), rowsAffected = rowsAffected,
    )

    fun failed(error: String?) = JobRunEntity(
        id = id, jobName = jobName, status = "failed",
        startedAt = startedAt, finishedAt = Instant.now(), error = error,
    )
}
