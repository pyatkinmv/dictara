package com.dictara.gateway.storage

import com.dictara.gateway.config.DictaraProperties
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** What goes to the bucket and how much memory one upload may reserve. */
class GcsAudioStorageTest {

    private val storage = mock(Storage::class.java)
    private val gcs = GcsAudioStorage(
        DictaraProperties(storage = DictaraProperties.StorageProps(gcs = DictaraProperties.GcsProps("b"))),
        storage,
    )

    /** The real client reads the stream to the end while it uploads; the hash is made from what it read. */
    private fun clientReadsTheStream() {
        `when`(
            storage.createFrom(
                ArgumentMatchers.any(BlobInfo::class.java), ArgumentMatchers.any(InputStream::class.java), ArgumentMatchers.anyInt(),
                *emptyArray<Storage.BlobWriteOption>(),
            )
        ).thenAnswer { (it.arguments[1] as InputStream).readBytes(); null }
    }

    @Test
    fun `an upload reserves a small buffer, not the client's default of 15 MiB`() {
        val bytes = "audio".toByteArray()
        val buffer = ArgumentCaptor.forClass(Int::class.java)

        gcs.upload(UUID.randomUUID(), "call.m4a", ByteArrayInputStream(bytes), bytes.size.toLong(), "audio/mp4")

        verify(storage).createFrom(
            ArgumentMatchers.any(BlobInfo::class.java), ArgumentMatchers.any(InputStream::class.java), buffer.capture(),
            *emptyArray<Storage.BlobWriteOption>(),
        )
        assertThat(buffer.value).isEqualTo(GcsAudioStorage.UPLOAD_BUFFER_BYTES)
        assertThat(GcsAudioStorage.UPLOAD_BUFFER_BYTES).isLessThan(15 * 1024 * 1024)
        assertThat(GcsAudioStorage.UPLOAD_BUFFER_BYTES % (256 * 1024))
            .describedAs("resumable uploads want multiples of 256 KiB").isZero()
    }

    @Test
    fun `the object is named after the day, the record and the file, and the hash is of the bytes sent`() {
        clientReadsTheStream()
        val bytes = "the recording".toByteArray()
        val id = UUID.randomUUID()
        val blob = ArgumentCaptor.forClass(BlobInfo::class.java)

        val result = gcs.upload(id, "20260608_213900.m4a", ByteArrayInputStream(bytes), bytes.size.toLong(), "audio/mp4")

        verify(storage).createFrom(
            blob.capture(), ArgumentMatchers.any(InputStream::class.java), ArgumentMatchers.anyInt(),
            *emptyArray<Storage.BlobWriteOption>(),
        )
        assertThat(blob.value.bucket).isEqualTo("b")
        assertThat(blob.value.name).matches("audio/\\d{4}-\\d{2}-\\d{2}/$id/20260608_213900\\.m4a")
        assertThat(blob.value.contentType).isEqualTo("audio/mp4")
        assertThat(result.ref.uri).isEqualTo("gs://b/" + blob.value.name)
        assertThat(result.contentHash)
            .isEqualTo(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
}
