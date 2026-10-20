package com.dictara.gateway.mcp

import jakarta.annotation.PostConstruct
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Settings of the MCP feature (`dictara.mcp.*`). The whole feature is kept in the `mcp` package, the `mcp` database
 * schema and `docs/mcp.md`; see there for how it works.
 */
@ConfigurationProperties(prefix = "dictara.mcp")
data class McpProperties(
    /** How long a token works after it is created. Fixed: the user is not asked. */
    val tokenTtlDays: Long = 90,
    /** From this many days before it ends a token is shown as "expires soon". */
    val expiringSoonDays: Long = 14,
    /** How many working tokens one user may hold. */
    val maxActiveTokens: Int = 10,
    /** Tool calls one token may make in a minute. */
    val rateLimitPerMinute: Int = 120,
    /** The text of one tool result is cut at this many characters, with a note on how to ask for the rest. */
    val maxResultChars: Int = 60_000,
)

@Configuration
@EnableConfigurationProperties(McpProperties::class)
class McpConfiguration

/**
 * Creates and migrates the `mcp` schema with a Flyway of its own (its own folder `db/mcp` and its own history table
 * `mcp.flyway_schema_history`), after the main migrations have run: the tables point at `users`.
 *
 * It is not a `Flyway` bean on purpose: Spring Boot sets its own up only when there is none, and that one must stay
 * the main one.
 */
@Component
@DependsOn("flywayInitializer")
class McpSchemaMigrator(private val dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun migrate() {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/mcp")
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .createSchemas(true)
            .table("flyway_schema_history")
            .load()
            .migrate()
        log.info("MCP schema migrated: {} migration(s) applied", result.migrationsExecuted)
    }

    companion object {
        const val SCHEMA = "mcp"
    }
}
