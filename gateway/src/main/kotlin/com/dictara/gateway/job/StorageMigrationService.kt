package com.dictara.gateway.job

import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class StorageMigrationService(
    private val audioStorage: AudioStorage,
    private val audioMetaRepo: AudioMetaRepository,
    private val jobTracker: JobTracker,
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            migrate()
        } catch (e: Exception) {
            log.error("Storage path migration failed", e)
        }
    }

    private fun migrate() = jobTracker.tracked("migrate_storage_paths") {
        val rows = jdbcTemplate.query(
            "SELECT storage_uri, MIN(created_at) FROM audio_meta WHERE storage_uri IS NOT NULL GROUP BY storage_uri"
        ) { rs, _ -> Pair(rs.getString(1), rs.getTimestamp(2).toInstant()) }

        val oldRows = rows.filter { (uri, _) -> isOldFormat(uri) }

        if (oldRows.isEmpty()) {
            log.info("Storage path migration: nothing to migrate")
            return@tracked 0
        }
        log.info("Storage path migration: migrating {} old-format URI(s)", oldRows.size)

        var migrated = 0
        for ((oldUri, createdAt) in oldRows) {
            val date = LocalDate.ofInstant(createdAt, ZoneId.systemDefault())
            val subpath = oldUri.removePrefix("gs://").substringAfter("/").substringAfter("audio/")
            val newObjectName = "audio/$date/$subpath"
            val newRef = audioStorage.copyObject(AudioRef(oldUri), newObjectName)
            val updated = audioMetaRepo.updateStorageUri(oldUri, newRef.uri)
            log.info("Migrated {} -> {} ({} row(s) updated)", oldUri, newRef.uri, updated)
            migrated++
        }
        migrated
    }

    private fun isOldFormat(uri: String): Boolean {
        val afterAudio = uri.substringAfter("/audio/", "")
        return afterAudio.isNotEmpty() && !afterAudio.matches(Regex("\\d{4}-\\d{2}-\\d{2}/.*"))
    }
}
