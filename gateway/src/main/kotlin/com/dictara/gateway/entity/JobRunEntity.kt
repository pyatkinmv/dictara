package com.dictara.gateway.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("job_runs")
class JobRunEntity(
    @Id val id: UUID? = null,
    val jobName: String,
    val status: String = "running",
    val startedAt: Instant = Instant.now(),
    val finishedAt: Instant? = null,
    val rowsAffected: Int? = null,
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
