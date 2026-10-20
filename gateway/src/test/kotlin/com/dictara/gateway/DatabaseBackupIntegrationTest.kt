package com.dictara.gateway

import com.dictara.gateway.job.DatabaseBackupService
import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.zip.GZIPInputStream

private fun <T> any(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

@SpringBootTest(webEnvironment = RANDOM_PORT)
class DatabaseBackupIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var databaseBackupService: DatabaseBackupService
    @MockBean lateinit var audioStorage: AudioStorage

    private val uploaded = ConcurrentHashMap<String, ByteArray>()

    @BeforeEach
    fun captureUploads() {
        uploaded.clear()
        doAnswer { uploaded[it.getArgument(0)] = it.getArgument(1); null }.`when`(audioStorage).uploadBytes(any(), any())
        jdbcTemplate.execute("DROP TABLE IF EXISTS backup_probe, backup_probe_empty")
        jdbcTemplate.execute("CREATE TABLE backup_probe (id uuid PRIMARY KEY, n int NOT NULL, note text, at timestamptz NOT NULL)")
        jdbcTemplate.execute("CREATE TABLE backup_probe_empty (id int)")
    }

    @AfterEach
    fun dropProbes() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS backup_probe, backup_probe_empty")
    }

    private fun backedUp(date: LocalDate, table: String): JsonNode {
        val gz = uploaded["backups/$date/$table.json.gz"] ?: error("no backup file for $table; got ${uploaded.keys}")
        return ObjectMapper().readTree(GZIPInputStream(ByteArrayInputStream(gz)).readAllBytes())
    }

    @Test
    fun `backup records completed job run`() {
        databaseBackupService.backup()

        val status = jdbcTemplate.queryForObject(
            "SELECT status FROM job_runs WHERE job_name = 'daily_db_backup' ORDER BY started_at DESC LIMIT 1",
            String::class.java
        )
        assertThat(status).isEqualTo("completed")
    }

    @Test
    fun `every row of a table lands in its file, past the fetch size, with its columns`() {
        // More rows than one fetch of the cursor (1000), so a bug that stops after the first batch shows.
        jdbcTemplate.execute("INSERT INTO backup_probe SELECT gen_random_uuid(), g, 'row ' || g, now() FROM generate_series(1, 2500) g")
        val today = LocalDate.now()

        val total = databaseBackupService.backupAll(today)

        val rows = backedUp(today, "backup_probe")
        assertThat(rows.isArray).isTrue()
        assertThat(rows.size()).isEqualTo(2500)
        assertThat(rows.map { it["n"].asInt() }.toSet()).hasSize(2500)
        assertThat(rows.map { it["id"].asText() }.toSet()).hasSize(2500)
        val first = rows.first { it["n"].asInt() == 7 }
        assertThat(first["note"].asText()).isEqualTo("row 7")
        assertThat(first["at"].isNull).isFalse()
        assertThat(total).isGreaterThanOrEqualTo(2500)
    }

    @Test
    fun `an empty table is an empty array, and the migration history is not backed up`() {
        val today = LocalDate.now()

        databaseBackupService.backupAll(today)

        assertThat(backedUp(today, "backup_probe_empty").size()).isZero()
        assertThat(uploaded.keys).noneMatch { it.contains("flyway_schema_history") }
        assertThat(uploaded.keys).contains("backups/$today/users.json.gz")
    }

    @Test
    fun `the tables of the mcp schema are backed up too, named with their schema`() {
        val today = LocalDate.now()
        val user = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", user)
        jdbcTemplate.update(
            "INSERT INTO mcp.access_tokens (id, user_id, name, token_hash, token_prefix, access, expires_at) VALUES (?, ?, 'backed up', ?, 'dtr_abcd', 'read', now() + interval '1 day')",
            UUID.randomUUID(), user, "hash-of-backup-test".toByteArray(),
        )

        databaseBackupService.backupAll(today)

        assertThat(uploaded.keys).contains("backups/$today/mcp.access_tokens.json.gz", "backups/$today/mcp.audit_log.json.gz")
        assertThat(uploaded.keys).noneMatch { it.contains("flyway_schema_history") } // neither schema's history
        val rows = backedUp(today, "mcp.access_tokens")
        assertThat(rows.map { it["name"].asText() }).contains("backed up")
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user)
    }
}
