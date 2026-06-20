package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioContentEntity
import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AudioMetaRepository : JpaRepository<AudioMetaEntity, UUID>

@Deprecated("audio_content table has been migrated to GCS — use GcsAudioStorage instead")
interface AudioContentRepository : JpaRepository<AudioContentEntity, UUID> {
    @org.springframework.data.jpa.repository.Query("SELECT a.audioId FROM AudioContentEntity a")
    fun findAllAudioIds(): List<UUID>
}
