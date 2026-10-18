package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioMetaEntity
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.UUID

interface AudioMetaRepository : CrudRepository<AudioMetaEntity, UUID> {

    /** Points every row of the same content at one object: the oldest of those in [liveUris] (objects that still
     *  exist in storage). Rows whose own object is gone are healed this way too; a hash with no live object is
     *  left alone. [liveUris] must not be empty. */
    @Modifying
    @Query("""
        UPDATE audio_meta am
        SET storage_uri = canonical.uri
        FROM (
            SELECT DISTINCT ON (content_hash) content_hash, storage_uri AS uri
            FROM audio_meta WHERE storage_uri IN (:liveUris)
            ORDER BY content_hash, created_at ASC
        ) canonical
        WHERE am.content_hash = canonical.content_hash
          AND am.storage_uri IS DISTINCT FROM canonical.uri
    """)
    fun deduplicateStorageUris(liveUris: Collection<String>): Int

    fun findByContentHashOrderByCreatedAtAsc(contentHash: String): List<AudioMetaEntity>

    @Modifying
    @Query("UPDATE audio_meta SET storage_uri = :uri WHERE id IN (:ids)")
    fun updateStorageUri(ids: Collection<UUID>, uri: String)

    @Modifying
    @Query("UPDATE audio_meta SET original_name = :name WHERE id IN (:ids)")
    fun updateOriginalName(ids: Collection<UUID>, name: String)

    @Query("SELECT storage_uri FROM audio_meta WHERE storage_uri IS NOT NULL")
    fun findAllStorageUris(): List<String>

    @Modifying
    @Query("UPDATE audio_meta SET duration_s = :durationS WHERE id = :id")
    fun updateDurationS(id: UUID, durationS: Double?)

    @Modifying
    @Query("UPDATE audio_meta SET metadata = :metadata WHERE id = :id")
    fun updateMetadata(id: UUID, metadata: JsonNode?)

    /** A date from the file name never replaces a better source (the container's own time, or a manual value). */
    @Modifying
    @Query("""
        UPDATE audio_meta SET recorded_at = :at, recorded_at_source = 'filename'
        WHERE id = :id AND (recorded_at_source IS NULL OR recorded_at_source = 'filename')
    """)
    fun updateRecordedAtFromFileName(id: UUID, at: Instant)

    /** The container's creation_time wins over a date from the name, and over nothing; not over a manual value. */
    @Modifying
    @Query("""
        UPDATE audio_meta SET recorded_at = :at, recorded_at_source = 'metadata'
        WHERE id = :id AND (recorded_at_source IS NULL OR recorded_at_source IN ('filename', 'metadata'))
    """)
    fun updateRecordedAtFromMetadata(id: UUID, at: Instant)
}
