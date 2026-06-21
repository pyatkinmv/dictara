package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AudioMetaRepository : JpaRepository<AudioMetaEntity, UUID> {

    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
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

    @Query("SELECT a.storageUri FROM AudioMetaEntity a WHERE a.storageUri IS NOT NULL")
    fun findAllStorageUris(): List<String>
}
