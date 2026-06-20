package com.dictara.gateway

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Singleton PostgreSQL container and WireMock server shared between
 * [AudioStorageIntegrationTest] and [DeduplicationIntegrationTest].
 *
 * Both classes use @MockBean AudioStorage and need the same transcriber URL —
 * sharing these resources lets Spring cache a single application context for both,
 * reducing the number of containers in CI from N to N-1 and avoiding OOM.
 */
object SharedTestInfrastructure {
    val postgres: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16").also { it.start() }
    }

    val wireMock: WireMockServer by lazy {
        WireMockServer(wireMockConfig().dynamicPort()).also { it.start() }
    }
}
