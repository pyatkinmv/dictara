package com.dictara.gateway.entity

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity @Table(name = "audio_meta")
class AudioMetaEntity(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @get:JvmName("getEntityId")
    val id: UUID? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    val user: UserEntity,
    @Column(name = "original_name", nullable = false) val originalName: String,
    @Column(name = "content_type", nullable = false) val contentType: String,
    @Column(name = "size_bytes", nullable = false) val sizeBytes: Long,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant = Instant.now(),
    /** gs://bucket/key when the audio is held in GCS (Cloud Run path); null when
     *  stored as a BLOB in [AudioContentEntity] (legacy/local-dev fallback path). */
    @Column(name = "storage_uri") val storageUri: String? = null,
    /** SHA-256 hex digest of the raw file bytes, set on upload. Null for records created before V12 migration. */
    @Column(name = "content_hash") val contentHash: String? = null,
) : Persistable<UUID> {

    // Spring Data JPA calls persist() when isNew()=true, merge() when false.
    // Without Persistable, a non-null id makes isNew()=false → merge() → Hibernate 6.5
    // generates a NEW UUID for the managed copy, discarding the pre-generated audioMetaId.
    // That breaks DatabaseAudioStorage: audio_content.audio_id = audioMetaId ≠ audio.id.
    @jakarta.persistence.Transient
    private var newEntity = true

    override fun getId(): UUID? = id
    override fun isNew(): Boolean = newEntity

    @PostPersist
    @PostLoad
    private fun markLoaded() { newEntity = false }
}
