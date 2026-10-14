package com.dictara.gateway.service

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
        service = AudioIngestService(audioMetaRepo, audioStorage)
    }

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
}
