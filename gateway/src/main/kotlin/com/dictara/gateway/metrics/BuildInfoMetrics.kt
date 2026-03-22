package com.dictara.gateway.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class BuildInfoMetrics(registry: MeterRegistry) {
    init {
        Gauge.builder("dictara_build_info") { 1.0 }
            .description("Build information (git_commit and build_time are common tags)")
            .register(registry)
    }
}
