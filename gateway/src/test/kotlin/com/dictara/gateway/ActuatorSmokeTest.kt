package com.dictara.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class ActuatorSmokeTest {

    @Autowired lateinit var rest: TestRestTemplate

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16")

        @DynamicPropertySource @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("dictara.transcriber.url") { "http://localhost:9999" }
        }
    }

    @Test
    fun `actuator health returns UP`() {
        val resp = rest.getForEntity("/actuator/health", String::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body).contains("\"status\":\"UP\"")
    }

    @Test
    fun `actuator info returns build metadata keys`() {
        val resp = rest.getForEntity("/actuator/info", String::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body).contains("git-commit")
        assertThat(resp.body).contains("build-time")
    }

    @Test
    fun `actuator prometheus returns metrics`() {
        val resp = rest.getForEntity("/actuator/prometheus", ByteArray::class.java)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.body).isNotEmpty()
    }
}
