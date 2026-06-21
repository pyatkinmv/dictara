package com.dictara.gateway.job

import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class StorageMaintenanceService(
    private val audioMetaRepo: AudioMetaRepository,
    private val audioStorage: AudioStorage,
    private val jobTracker: JobTracker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val ORPHAN_GRACE_PERIOD: Duration = Duration.ofHours(1)
    }

    @Scheduled(cron = "0 0 17 * * *")  // TEMPORARY for testing: 17:00 UTC = 21:00 Yerevan
    fun deduplicateStorageUris() = jobTracker.tracked("dedup_storage_uris") {
        val updated = audioMetaRepo.deduplicateStorageUris()
        log.info("Dedup: {} audio_meta rows updated to canonical storage URI", updated)
        updated
    }

    @Scheduled(cron = "0 30 17 * * *")  // TEMPORARY for testing: 17:30 UTC = 21:30 Yerevan
    fun cleanupOrphanedGcsObjects() = jobTracker.tracked("cleanup_orphaned_gcs_objects") {
        val referencedUris = audioMetaRepo.findAllStorageUris().toHashSet()
        log.info("GCS orphan cleanup: {} URIs referenced in DB", referencedUris.size)

        val gcsObjects = audioStorage.listObjects()
        log.info("GCS orphan cleanup: {} objects found in bucket", gcsObjects.size)

        val cutoff = Instant.now().minus(ORPHAN_GRACE_PERIOD)
        var deleted = 0
        var skippedReferenced = 0
        var skippedTooNew = 0

        for (obj in gcsObjects) {
            when {
                obj.uri in referencedUris -> skippedReferenced++
                obj.createdAt.isAfter(cutoff) -> {
                    log.debug("GCS orphan cleanup: skipping {} (too new: {})", obj.uri, obj.createdAt)
                    skippedTooNew++
                }
                else -> {
                    log.info("GCS orphan cleanup: deleting unreferenced object {}", obj.uri)
                    audioStorage.delete(AudioRef(obj.uri))
                    deleted++
                }
            }
        }
        log.info("GCS orphan cleanup done: deleted={}, skipped_referenced={}, skipped_too_new={}",
            deleted, skippedReferenced, skippedTooNew)
        deleted
    }
}
