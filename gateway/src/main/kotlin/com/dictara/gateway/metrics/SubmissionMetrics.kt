package com.dictara.gateway.metrics

import com.dictara.gateway.repository.SubmissionRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class SubmissionMetrics(registry: MeterRegistry, repo: SubmissionRepository) {
    init {
        listOf("pending", "processing", "summarizing", "done", "failed").forEach { status ->
            Gauge.builder("dictara_jobs_total") { repo.countByStatus(status).toDouble() }
                .tag("status", status)
                .description("Number of submissions by status")
                .register(registry)
        }
    }
}
