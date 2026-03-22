package com.dictara.gateway.metrics

import com.dictara.gateway.repository.TelegramDeliveryRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class TelegramDeliveryMetrics(registry: MeterRegistry, repo: TelegramDeliveryRepository) {
    init {
        Gauge.builder("dictara_delivery_undelivered") { repo.countUndelivered().toDouble() }
            .description("Telegram deliveries not yet delivered (eligible for retry)")
            .register(registry)

        Gauge.builder("dictara_delivery_exhausted") { repo.countExhausted().toDouble() }
            .description("Telegram deliveries that exhausted all retry attempts")
            .register(registry)
    }
}
