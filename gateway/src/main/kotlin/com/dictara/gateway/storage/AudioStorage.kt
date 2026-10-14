package com.dictara.gateway.storage

import java.io.InputStream
import java.time.Instant
import java.util.UUID

data class UploadResult(val ref: AudioRef, val contentHash: String)
data class StorageObject(val uri: String, val createdAt: Instant)

interface AudioStorage {
    fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): UploadResult
    fun download(ref: AudioRef): InputStream?
    fun listObjects(): List<StorageObject>
    fun delete(ref: AudioRef)
    fun uploadBytes(objectName: String, bytes: ByteArray)

    /** Opens [ref] for reading starting at byte [offset] (0 = from the start), without transferring the bytes
     *  before it — for serving HTTP `Range` requests (audio playback seeking) cheaply. Null if unavailable. */
    fun openFrom(ref: AudioRef, offset: Long): InputStream?
}
