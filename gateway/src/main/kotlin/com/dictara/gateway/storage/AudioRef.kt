package com.dictara.gateway.storage

import java.util.UUID

sealed class AudioRef {
    data class Gcs(val uri: String) : AudioRef()
    @Deprecated("audio_content has been migrated to GCS — Db refs no longer exist in production")
    data class Db(val audioMetaId: UUID) : AudioRef()

    /** Column value to persist in audio_meta.storage_uri. */
    val storageUri: String? get() = when (this) {
        is Gcs -> uri
        is Db  -> null
    }

    companion object {
        fun from(audioMetaId: UUID, storageUri: String?): AudioRef =
            if (storageUri != null) Gcs(storageUri) else Db(audioMetaId)
    }
}
