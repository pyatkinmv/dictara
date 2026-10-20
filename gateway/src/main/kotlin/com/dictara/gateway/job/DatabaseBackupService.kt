package com.dictara.gateway.job

import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

@Profile("!local")
@Service
class DatabaseBackupService(
    private val audioStorage: AudioStorage,
    private val jobTracker: JobTracker,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // PostgreSQL only streams a result set through a cursor (fetchSize) while autocommit is off, i.e. inside a transaction.
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val rowWriter = objectMapper.writer().without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
    private val rowMapper = ColumnMapRowMapper()

    @Scheduled(cron = "0 45 16 * * *")
    fun backup() = jobTracker.tracked("daily_db_backup") { backupAll(LocalDate.now()) }

    internal fun backupAll(date: LocalDate): Int {
        // The main schema and the `mcp` schema (the MCP tokens and their audit log). A table of the main schema is
        // named as it is, one of another schema as `schema.table`, which is also its file name.
        val tables = jdbcTemplate.query(
            """SELECT table_schema, table_name FROM information_schema.tables
               WHERE table_schema IN ('public', 'mcp') AND table_type = 'BASE TABLE'
               ORDER BY table_schema, table_name"""
        ) { rs, _ -> rs.getString("table_schema") to rs.getString("table_name") }
            .filter { (_, name) -> name != "flyway_schema_history" }
            .map { (schema, name) -> if (schema == "public") name else "$schema.$name" }

        log.info("DB backup: backing up {} tables for {}", tables.size, date)
        var totalRows = 0
        for (table in tables) {
            val (gzipped, rows) = dumpTable(table)
            audioStorage.uploadBytes("backups/$date/$table.json.gz", gzipped)
            log.info("DB backup: {} rows from {}", rows, table)
            totalRows += rows
        }
        return totalRows
    }

    /** The table as a gzipped JSON array of rows, written row by row: only the compressed bytes are held in memory,
     *  never all rows (transcript_segments alone is hundreds of thousands of rows and ran the 2 GB VM out of heap
     *  when the whole table was read into a list first). The file format is the same as before. */
    private fun dumpTable(table: String): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var rows = 0
        readOnly.executeWithoutResult {
            GZIPOutputStream(out).use { gzip ->
                objectMapper.createGenerator(gzip).use { json ->
                    json.writeStartArray()
                    jdbcTemplate.query(
                        PreparedStatementCreator { con -> con.prepareStatement("SELECT * FROM ${quoted(table)}").apply { fetchSize = FETCH_SIZE } },
                        RowCallbackHandler { rs -> rowWriter.writeValue(json, rowMapper.mapRow(rs, rows++)) },
                    )
                    json.writeEndArray()
                }
            }
        }
        return out.toByteArray() to rows
    }

    /** `users` is `"users"`, `mcp.access_tokens` is `"mcp"."access_tokens"`. The names come from the catalog, not from a client. */
    private fun quoted(table: String) = table.split('.').joinToString(".") { "\"$it\"" }

    private companion object {
        const val FETCH_SIZE = 1000
    }
}
