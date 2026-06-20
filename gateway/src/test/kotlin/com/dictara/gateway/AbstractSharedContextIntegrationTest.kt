package com.dictara.gateway

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base class for integration tests that share [SharedTestInfrastructure] (one PostgreSQL container
 * + one WireMock server). Subclasses automatically get:
 *   - [DynamicPropertySource] wiring to the shared infra (Spring caches one context for all subclasses
 *     that also share the same @MockBean set)
 *   - A [@BeforeEach][BeforeEach] that resets WireMock and truncates all submission-related tables
 *     atomically via CASCADE before each test
 */
abstract class AbstractSharedContextIntegrationTest {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun sharedInfraProps(registry: DynamicPropertyRegistry) {
            val pg = SharedTestInfrastructure.postgres
            registry.add("spring.datasource.url") { pg.jdbcUrl }
            registry.add("spring.datasource.username") { pg.username }
            registry.add("spring.datasource.password") { pg.password }
            registry.add("dictara.transcriber.url") { SharedTestInfrastructure.wireMock.baseUrl() }
            registry.add("dictara.transcriber.poll-interval-ms") { "100" }
        }
    }

    @BeforeEach
    fun cleanSharedState() {
        SharedTestInfrastructure.wireMock.resetAll()
        // CASCADE truncates the entire FK chain:
        // audio_meta → submissions → stage_attempts, telegram_deliveries, transcripts, diarizations, summaries
        // The ACCESS EXCLUSIVE lock waits for any concurrent OrchestratorService transaction to finish,
        // avoiding the FK violation that sequential DELETEs would race with.
        jdbcTemplate.execute("TRUNCATE audio_meta CASCADE")
    }
}
