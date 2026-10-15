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
 * V38 let everybody regenerate a summary once and kept that as the state 'done'; V39 counts the times instead and puts
 * the limit in the plans. Starts from V38, with a transcription as production has them: never regenerated, being
 * regenerated, and regenerated (used).
 */
class SummaryRegenerationByPlanMigrationTest {

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
        flyway("38").migrate()
    }

    @AfterEach
    fun dropScratchDatabase() {
        withConnection(adminUrl()) { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName WITH (FORCE)") }
    }

    private fun submission(regeneration: String?): UUID {
        val userId = UUID.randomUUID()
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        withConnection { c ->
            c.prepareStatement("INSERT INTO users(id) VALUES (?)").apply { setObject(1, userId) }.execute()
            c.prepareStatement(
                "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'a.m4a', 'audio/mp4', 1, ?, ?)",
            ).apply { setObject(1, audioId); setObject(2, userId); setString(3, "gs://t/$audioId"); setString(4, "h-$audioId") }.execute()
            c.prepareStatement(
                "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status, summary_regeneration, summary_regenerated_at) " +
                    "VALUES (?, ?, ?, 'fast', 'auto', false, 'auto', 'web', 'done', ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)",
            ).apply { setObject(1, submissionId); setObject(2, userId); setObject(3, audioId); setString(4, regeneration); setString(5, regeneration) }.execute()
        }
        return submissionId
    }

    private fun row(id: UUID): Pair<Int, String?> = withConnection { c ->
        c.prepareStatement("SELECT summary_regenerations, summary_regeneration FROM submissions WHERE id = ?").apply { setObject(1, id) }.executeQuery().use { rs ->
            rs.next(); rs.getInt(1) to rs.getString(2)
        }
    }

    private fun constraints(plan: String): String = withConnection { c ->
        c.prepareStatement("SELECT constraints::text FROM plans WHERE name = ?").apply { setString(1, plan) }.executeQuery().use { rs -> rs.next(); rs.getString(1) }
    }

    @Test
    fun `a regeneration that was used counts as one, one that never was as none, and one running stays running`() {
        val never = submission(null)
        val used = submission("done")
        val running = submission("running")

        flyway().migrate()

        assertThat(row(never)).isEqualTo(0 to null)
        assertThat(row(used)).isEqualTo(1 to null)
        assertThat(row(running)).isEqualTo(0 to "running")
    }

    @Test
    fun `free and anonymous may regenerate a recording once, ultra has no limit`() {
        flyway().migrate()

        assertThat(constraints("free")).contains("\"regenerations_per_transcription\": 1").contains("\"monthly_submissions\": 10")
        assertThat(constraints("anonymous")).contains("\"regenerations_per_transcription\": 1").contains("\"lifetime_submissions\": 2")
        assertThat(constraints("unlimited")).doesNotContain("regenerations_per_transcription")
    }
}
