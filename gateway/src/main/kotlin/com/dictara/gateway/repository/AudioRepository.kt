package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioContentEntity
import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AudioMetaRepository : JpaRepository<AudioMetaEntity, UUID> {
    @Modifying
    @Transactional
    @Query("UPDATE AudioMetaEntity a SET a.storageUri = :uri, a.contentHash = :hash WHERE a.id = :id")
    fun updateStorageUri(@Param("id") id: UUID, @Param("uri") uri: String, @Param("hash") hash: String?)
}

@Deprecated("audio_content table has been migrated to GCS — use GcsAudioStorage instead")
interface AudioContentRepository : JpaRepository<AudioContentEntity, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT a.audioId FROM AudioContentEntity a")
    fun findAllAudioIds(): List<UUID>
}
