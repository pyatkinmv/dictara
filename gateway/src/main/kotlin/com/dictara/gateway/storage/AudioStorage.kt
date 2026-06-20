package com.dictara.gateway.storage

import java.io.InputStream
import java.util.UUID

interface AudioStorage {
    /** Persists audio and returns the storage URI, or null when stored internally (no external URI). */
    fun upload(audioMetaId: UUID, fileName: String, inputStream: InputStream, sizeBytes: Long, contentType: String): String?

    /** Retrieves audio data. GCS implementations use [storageUri]; DB implementations use [audioMetaId]. */
    fun download(audioMetaId: UUID, storageUri: String?): InputStream?
}
