package com.dictara.bot

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import java.net.InetSocketAddress

fun main() {
    val token = System.getenv("TELEGRAM_TOKEN")
        ?: error("TELEGRAM_TOKEN env var is required")
    val dictaraUrl = System.getenv("GATEWAY_URL")
        ?: System.getenv("TRANSCRIBER_URL")
        ?: "http://localhost:8080"
    val apiUrl = System.getenv("TELEGRAM_API_URL")

    val options = DefaultBotOptions()
    if (apiUrl != null) {
        options.baseUrl = "$apiUrl/bot"
    }

    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    registry.config().commonTags(
        "git_commit", System.getenv("GIT_COMMIT") ?: "unknown",
        "build_time", System.getenv("BUILD_TIME") ?: "unknown"
    )
    Gauge.builder("dictara_build_info") { 1.0 }
        .description("Build information (git_commit and build_time are common tags)")
        .register(registry)
    JvmMemoryMetrics().bindTo(registry)
    JvmGcMetrics().bindTo(registry)
    JvmThreadMetrics().bindTo(registry)
    ProcessorMetrics().bindTo(registry)

    val api = TelegramBotsApi(DefaultBotSession::class.java)
    api.registerBot(DictaraBot(token, dictaraUrl, options, registry))

    HttpServer.create(InetSocketAddress(9090), 0).apply {
        createContext("/metrics") { ex ->
            val body = registry.scrape().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }

    println("dictara-tg-bot started [commit=${System.getenv("GIT_COMMIT") ?: "unknown"} built=${System.getenv("BUILD_TIME") ?: "unknown"}]. Metrics on :9090/metrics")
}
