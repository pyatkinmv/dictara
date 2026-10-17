package com.dictara.gateway.service

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.UploadResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockMultipartFile
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("UNCHECKED_CAST")
private fun <T> anyK(): T {
    ArgumentMatchers.any<T>()
    return null as T
}

/** The file is stored once per content: the hash is taken first, and a live object with it is reused. */
@ExtendWith(MockitoExtension::class)
class AudioIngestServiceTest {

    @Mock lateinit var audioMetaRepo: AudioMetaRepository
    @Mock lateinit var audioStorage: AudioStorage

    private val bytes = "the same recording".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val file = MockMultipartFile("file", "call.m4a", "audio/mp4", bytes)
    private lateinit var service: AudioIngestService

    @BeforeEach
    fun setUp() {
        service = serviceWith(maxConcurrentUploads = 4)
    }

    private fun serviceWith(maxConcurrentUploads: Int) = AudioIngestService(
        audioMetaRepo, audioStorage,
        DictaraProperties(storage = DictaraProperties.StorageProps(maxConcurrentUploads = maxConcurrentUploads)),
    )

    private fun row(uri: String?) = AudioMetaEntity(
        userId = UUID.randomUUID(), originalName = "x", contentType = "audio/mp4", sizeBytes = 1, contentHash = hash, storageUri = uri,
    )

    private fun stubUpload(uri: String) {
        `when`(audioStorage.upload(anyK<UUID>(), anyK<String>(), anyK<InputStream>(), ArgumentMatchers.anyLong(), anyK<String>()))
            .thenReturn(UploadResult(AudioRef(uri), "storage-hash"))
    }

    @Test
    fun `new content is uploaded`() {
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(hash)).thenReturn(emptyList())
        stubUpload("gs://b/new")

        val result = service.store(UUID.randomUUID(), "call.m4a", file, "audio/mp4")

        assertThat(result.ref.uri).isEqualTo("gs://b/new")
    }

    @Test
    fun `content that is already stored is not uploaded again, whoever stored it`() {
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(hash)).thenReturn(listOf(row("gs://b/live")))
        `when`(audioStorage.isGone(AudioRef("gs://b/live"))).thenReturn(false)

        val result = service.store(UUID.randomUUID(), "call.m4a", file, "audio/mp4")

        assertThat(result.ref.uri).isEqualTo("gs://b/live")
        assertThat(result.contentHash).isEqualTo(hash)
        verify(audioStorage, never()).upload(anyK<UUID>(), anyK<String>(), anyK<InputStream>(), ArgumentMatchers.anyLong(), anyK<String>())
    }

    @Test
    fun `a stored object that is gone does not count, the file is uploaded`() {
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(hash)).thenReturn(listOf(row("gs://b/dead")))
        `when`(audioStorage.isGone(AudioRef("gs://b/dead"))).thenReturn(true)
        stubUpload("gs://b/new")

        assertThat(service.store(UUID.randomUUID(), "call.m4a", file, "audio/mp4").ref.uri).isEqualTo("gs://b/new")
    }

    @Test
    fun `the first live copy among several is the one reused`() {
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(hash))
            .thenReturn(listOf(row("gs://b/dead"), row("gs://b/live"), row(null)))
        `when`(audioStorage.isGone(AudioRef("gs://b/dead"))).thenReturn(true)
        `when`(audioStorage.isGone(AudioRef("gs://b/live"))).thenReturn(false)

        assertThat(service.store(UUID.randomUUID(), "call.m4a", file, "audio/mp4").ref.uri).isEqualTo("gs://b/live")
    }

    // ── a burst of uploads: only a few at a time reach storage ──────────────────────────────────────────────

    private fun fileNo(n: Int) = MockMultipartFile("file", "f$n.m4a", "audio/mp4", "recording $n".toByteArray())

    @Test
    fun `no more uploads than the limit are on their way to storage at once, and every file is stored`() {
        service = serviceWith(maxConcurrentUploads = 3)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val finished = AtomicInteger()
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(ArgumentMatchers.anyString())).thenReturn(emptyList())
        `when`(audioStorage.upload(anyK<UUID>(), anyK<String>(), anyK<InputStream>(), ArgumentMatchers.anyLong(), anyK<String>()))
            .thenAnswer {
                peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(40)
                inFlight.decrementAndGet()
                finished.incrementAndGet()
                UploadResult(AudioRef("gs://b/${it.arguments[0]}"), "h")
            }

        val pool = Executors.newFixedThreadPool(30)
        try {
            val results = (1..30)
                .map { n -> pool.submit<UploadResult> { service.store(UUID.randomUUID(), "f$n.m4a", fileNo(n), "audio/mp4") } }
                .map { it.get(30, TimeUnit.SECONDS) }

            assertThat(results).hasSize(30)
            assertThat(results.map { it.ref.uri }.toSet()).describedAs("every file got its own object").hasSize(30)
        } finally {
            pool.shutdownNow()
        }
        assertThat(finished.get()).isEqualTo(30)
        assertThat(peak.get()).describedAs("uploads at the same moment").isEqualTo(3)
    }

    @Test
    fun `a failed upload gives its place back, the files behind it are still stored`() {
        service = serviceWith(maxConcurrentUploads = 1)
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(ArgumentMatchers.anyString())).thenReturn(emptyList())
        val calls = AtomicInteger()
        `when`(audioStorage.upload(anyK<UUID>(), anyK<String>(), anyK<InputStream>(), ArgumentMatchers.anyLong(), anyK<String>()))
            .thenAnswer {
                if (calls.incrementAndGet() == 1) throw IllegalStateException("bucket unavailable")
                UploadResult(AudioRef("gs://b/ok"), "h")
            }

        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            service.store(UUID.randomUUID(), "a.m4a", fileNo(1), "audio/mp4")
        }
        // with a single place, a place that was not given back would hang this call
        val pool = Executors.newSingleThreadExecutor()
        try {
            val second = pool.submit<UploadResult> { service.store(UUID.randomUUID(), "b.m4a", fileNo(2), "audio/mp4") }
            assertThat(second.get(5, TimeUnit.SECONDS).ref.uri).isEqualTo("gs://b/ok")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `content that is already stored does not wait for a place, even when all of them are taken`() {
        service = serviceWith(maxConcurrentUploads = 1)
        val knownHash = MessageDigest.getInstance("SHA-256").digest("recording 2".toByteArray()).joinToString("") { "%02x".format(it) }
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(ArgumentMatchers.anyString())).thenAnswer {
            if (it.arguments[0] == knownHash) listOf(row("gs://b/live")) else emptyList()
        }
        `when`(audioStorage.isGone(AudioRef("gs://b/live"))).thenReturn(false)
        val taken = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(audioStorage.upload(anyK<UUID>(), anyK<String>(), anyK<InputStream>(), ArgumentMatchers.anyLong(), anyK<String>()))
            .thenAnswer {
                taken.countDown()
                release.await(10, TimeUnit.SECONDS)
                UploadResult(AudioRef("gs://b/slow"), "h")
            }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val slow = pool.submit<UploadResult> { service.store(UUID.randomUUID(), "slow.m4a", fileNo(1), "audio/mp4") }
            assertThat(taken.await(5, TimeUnit.SECONDS)).isTrue() // the only place is now taken
            val known = pool.submit<UploadResult> { service.store(UUID.randomUUID(), "known.m4a", fileNo(2), "audio/mp4") }

            assertThat(known.get(5, TimeUnit.SECONDS).ref.uri).isEqualTo("gs://b/live")
            release.countDown()
            assertThat(slow.get(5, TimeUnit.SECONDS).ref.uri).isEqualTo("gs://b/slow")
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a limit below one still lets files through, one at a time`() {
        service = serviceWith(maxConcurrentUploads = 0)
        `when`(audioMetaRepo.findByContentHashOrderByCreatedAtAsc(ArgumentMatchers.anyString())).thenReturn(emptyList())
        stubUpload("gs://b/new")

        assertThat(service.store(UUID.randomUUID(), "call.m4a", fileNo(1), "audio/mp4").ref.uri).isEqualTo("gs://b/new")
    }

    @Test
    fun `the limit is four unless set otherwise`() {
        assertThat(DictaraProperties().storage.maxConcurrentUploads).isEqualTo(4)
    }
}
