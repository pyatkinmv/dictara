package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface AudioMetaRepository : CrudRepository<AudioMetaEntity, UUID> {

    @Query("""
        UPDATE audio_meta am
        SET storage_uri = canonical.uri
        FROM (
            SELECT DISTINCT ON (content_hash) content_hash, storage_uri AS uri
            FROM audio_meta WHERE storage_uri IS NOT NULL
            ORDER BY content_hash, created_at ASC
        ) canonical
        WHERE am.content_hash = canonical.content_hash
          AND am.storage_uri != canonical.uri
    """)
    fun deduplicateStorageUris(): Int

    @Query("SELECT storage_uri FROM audio_meta WHERE storage_uri IS NOT NULL")
    fun findAllStorageUris(): List<String>
}
