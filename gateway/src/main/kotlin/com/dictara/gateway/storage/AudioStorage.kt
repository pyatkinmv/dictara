package com.dictara.gateway.storage

import java.io.InputStream
import java.util.UUID

interface AudioStorage {
    fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): AudioRef
    fun download(ref: AudioRef): InputStream?
}
