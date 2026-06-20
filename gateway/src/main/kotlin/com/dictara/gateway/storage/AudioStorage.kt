package com.dictara.gateway.storage

import java.io.InputStream
import java.util.UUID

data class UploadResult(val ref: AudioRef, val contentHash: String)

interface AudioStorage {
    fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): UploadResult
    fun download(ref: AudioRef): InputStream?
}
