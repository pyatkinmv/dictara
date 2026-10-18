package com.dictara.gateway.entity

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("audio_meta")
class AudioMetaEntity(
    @Id @get:JvmName("getIdValue") val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: Instant = Instant.now(),
    val storageUri: String? = null,
    val contentHash: String,
    var durationS: Double? = null,
    /** The audio container's own facts (codec, bit rate, tags such as `creation_time`) — whatever the
     *  transcriber's `ffprobe` found on the original file; opaque here, just stored. Null until the
     *  transcription finishes, or when nothing useful was found. */
    var metadata: JsonNode? = null,
    /** When the recording was made (see [RecordedAt]); null when nothing tells it. */
    var recordedAt: Instant? = null,
    /** Where [recordedAt] came from: `metadata`, `filename` or `manual`. */
    var recordedAtSource: String? = null,
) : Persistable<UUID> {
    @Transient var _isNew: Boolean = false
    override fun getId(): UUID = id
    override fun isNew(): Boolean = _isNew
}
