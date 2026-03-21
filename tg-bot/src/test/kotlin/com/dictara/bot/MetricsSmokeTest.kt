package com.dictara.bot

import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsSmokeTest {

    @Test
    fun `metrics registry initializes and exposes JVM metrics`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)

        val scrape = registry.scrape()
        assertTrue(scrape.contains("jvm_memory"), "Expected jvm_memory in scrape output")
        assertTrue(scrape.contains("jvm_threads"), "Expected jvm_threads in scrape output")
    }

    @Test
    fun `common tags are applied to all metrics`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registry.config().commonTags("git_commit", "abc123", "build_time", "2026-01-01T00:00:00Z")
        JvmMemoryMetrics().bindTo(registry)

        val scrape = registry.scrape()
        assertTrue(scrape.contains("git_commit=\"abc123\""), "Expected git_commit tag in scrape output")
    }
}
