package com.dictara.gateway.job

import com.dictara.gateway.storage.AudioStorage
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

@Service
class DatabaseBackupService(
    private val audioStorage: AudioStorage,
    private val jobTracker: JobTracker,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 16 * * *")
    fun backup() = jobTracker.tracked("daily_db_backup") {
        val date = LocalDate.now()
        val tables = jdbcTemplate.queryForList(
            """SELECT table_name FROM information_schema.tables
               WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
               ORDER BY table_name""",
            String::class.java
        ).filter { it != "flyway_schema_history" }

        log.info("DB backup: backing up {} tables for {}", tables.size, date)
        var totalRows = 0
        for (table in tables) {
            val rows = jdbcTemplate.queryForList("SELECT * FROM $table")
            val gzipped = gzip(objectMapper.writeValueAsBytes(rows))
            audioStorage.uploadBytes("backups/$date/$table.json.gz", gzipped)
            log.info("DB backup: {} rows from {}", rows.size, table)
            totalRows += rows.size
        }
        totalRows
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
