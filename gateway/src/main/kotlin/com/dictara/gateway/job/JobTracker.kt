package com.dictara.gateway.job

import com.dictara.gateway.entity.JobRunEntity
import com.dictara.gateway.repository.JobRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class JobTracker(private val jobRunRepo: JobRunRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> tracked(name: String, block: () -> T): T {
        val run = jobRunRepo.save(JobRunEntity(jobName = name))
        val startedAt = Instant.now()
        log.info("Job [{}] started (run={})", name, run.id)
        try {
            val result = block()
            val elapsed = Duration.between(startedAt, Instant.now()).toMillis()
            val rows = result as? Int
            jobRunRepo.save(run.completed(rowsAffected = rows))
            log.info("Job [{}] completed in {}ms, rows_affected={} (run={})", name, elapsed, rows, run.id)
            return result
        } catch (e: Exception) {
            val elapsed = Duration.between(startedAt, Instant.now()).toMillis()
            jobRunRepo.save(run.failed(e.message))
            log.error("Job [{}] failed after {}ms: {} (run={})", name, elapsed, e.message, run.id)
            throw e
        }
    }
}
