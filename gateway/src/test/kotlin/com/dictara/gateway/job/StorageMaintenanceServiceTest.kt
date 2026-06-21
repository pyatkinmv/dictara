package com.dictara.gateway.job

import com.dictara.gateway.entity.JobRunEntity
import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.repository.JobRunRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.StorageObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class StorageMaintenanceServiceTest {

    @Mock lateinit var audioMetaRepo: AudioMetaRepository
    @Mock lateinit var audioStorage: AudioStorage
    @Mock lateinit var jobRunRepo: JobRunRepository

    lateinit var service: StorageMaintenanceService

    private val persistedRun = JobRunEntity(jobName = "test", id = UUID.randomUUID())

    @BeforeEach
    fun setup() {
        `when`(jobRunRepo.save(any())).thenReturn(persistedRun)
        service = StorageMaintenanceService(audioMetaRepo, audioStorage, JobTracker(jobRunRepo))
    }

    // --- deduplicateStorageUris ---

    @Test
    fun `deduplicateStorageUris delegates to repository and returns count`() {
        `when`(audioMetaRepo.deduplicateStorageUris()).thenReturn(5)

        service.deduplicateStorageUris()

        verify(audioMetaRepo).deduplicateStorageUris()
    }

    // --- cleanupOrphanedGcsObjects ---

    @Test
    fun `cleanupOrphanedGcsObjects deletes unreferenced object older than grace period`() {
        val orphanUri = "gs://bucket/audio/orphan/file.mp4"
        val old = Instant.now().minus(StorageMaintenanceService.ORPHAN_GRACE_PERIOD).minusSeconds(60)

        `when`(audioMetaRepo.findAllStorageUris()).thenReturn(emptyList())
        `when`(audioStorage.listObjects()).thenReturn(listOf(StorageObject(orphanUri, old)))

        service.cleanupOrphanedGcsObjects()

        verify(audioStorage).delete(AudioRef(orphanUri))
    }

    @Test
    fun `cleanupOrphanedGcsObjects does not delete referenced objects`() {
        val referencedUri = "gs://bucket/audio/used/file.mp4"
        val old = Instant.now().minus(StorageMaintenanceService.ORPHAN_GRACE_PERIOD).minusSeconds(60)

        `when`(audioMetaRepo.findAllStorageUris()).thenReturn(listOf(referencedUri))
        `when`(audioStorage.listObjects()).thenReturn(listOf(StorageObject(referencedUri, old)))

        service.cleanupOrphanedGcsObjects()

        verify(audioStorage, never()).delete(any())
    }

    @Test
    fun `cleanupOrphanedGcsObjects skips objects newer than grace period`() {
        val recentUri = "gs://bucket/audio/recent/file.mp4"
        val tooNew = Instant.now().minusSeconds(30)

        `when`(audioMetaRepo.findAllStorageUris()).thenReturn(emptyList())
        `when`(audioStorage.listObjects()).thenReturn(listOf(StorageObject(recentUri, tooNew)))

        service.cleanupOrphanedGcsObjects()

        verify(audioStorage, never()).delete(any())
    }

    @Test
    fun `cleanupOrphanedGcsObjects deletes only orphans among mixed objects`() {
        val referencedUri = "gs://bucket/audio/ref/file.mp4"
        val orphanUri = "gs://bucket/audio/orphan/file.mp4"
        val recentOrphanUri = "gs://bucket/audio/recent-orphan/file.mp4"
        val old = Instant.now().minus(StorageMaintenanceService.ORPHAN_GRACE_PERIOD).minusSeconds(60)
        val tooNew = Instant.now().minusSeconds(30)

        `when`(audioMetaRepo.findAllStorageUris()).thenReturn(listOf(referencedUri))
        `when`(audioStorage.listObjects()).thenReturn(listOf(
            StorageObject(referencedUri, old),
            StorageObject(orphanUri, old),
            StorageObject(recentOrphanUri, tooNew),
        ))

        service.cleanupOrphanedGcsObjects()

        verify(audioStorage).delete(AudioRef(orphanUri))
        verify(audioStorage, never()).delete(AudioRef(referencedUri))
        verify(audioStorage, never()).delete(AudioRef(recentOrphanUri))
    }
}
