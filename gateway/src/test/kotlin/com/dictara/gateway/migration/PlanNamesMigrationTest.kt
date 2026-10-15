package com.dictara.gateway.migration

import com.dictara.gateway.SharedTestInfrastructure
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * Production's plan names had been edited by hand ("Ultra Plan", "Free Plan"), which the migrations never knew about.
 * V36 and V37 bring them to the short names, so this starts from a database as production looks, not as the earlier
 * migrations left it.
 */
class PlanNamesMigrationTest {

    private lateinit var dbName: String
    private lateinit var url: String

    private fun adminUrl(): String = SharedTestInfrastructure.jdbcUrl
    private fun urlFor(db: String): String = adminUrl().replace(Regex("(jdbc:postgresql://[^/]+/)([^?]+)"), "$1$db")

    private fun <T> withConnection(target: String = url, block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(target, SharedTestInfrastructure.dbUsername, SharedTestInfrastructure.dbPassword).use(block)

    private fun flyway(target: String? = null): Flyway = Flyway.configure()
        .dataSource(url, SharedTestInfrastructure.dbUsername, SharedTestInfrastructure.dbPassword)
        .apply { if (target != null) target(target) }
        .load()

    @BeforeEach
    fun createScratchDatabase() {
        dbName = "migration_test_" + UUID.randomUUID().toString().replace("-", "")
        withConnection(adminUrl()) { it.createStatement().execute("CREATE DATABASE $dbName") }
        url = urlFor(dbName)
        flyway("35").migrate() // where production was before the plan names were touched
    }

    @AfterEach
    fun dropScratchDatabase() {
        withConnection(adminUrl()) { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName WITH (FORCE)") }
    }

    private fun displayNames(): Map<String, String> = withConnection { c ->
        c.createStatement().executeQuery("SELECT name, display_name FROM plans").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
        }
    }

    @Test
    fun `plan names that were edited by hand on production end up short`() {
        withConnection { c ->
            c.createStatement().execute("UPDATE plans SET display_name = 'Ultra Plan' WHERE name = 'unlimited'")
            c.createStatement().execute("UPDATE plans SET display_name = 'Free Plan' WHERE name = 'free'")
        }

        flyway().migrate()

        assertThat(displayNames()).containsEntry("free", "Free").containsEntry("unlimited", "Ultra").containsEntry("anonymous", "Guest")
    }

    @Test
    fun `a database whose names were never edited ends up the same`() {
        flyway().migrate()

        assertThat(displayNames()).containsEntry("free", "Free").containsEntry("unlimited", "Ultra").containsEntry("anonymous", "Guest")
    }
}
