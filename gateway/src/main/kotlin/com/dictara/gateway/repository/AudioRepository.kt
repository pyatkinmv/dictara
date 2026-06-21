package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface AudioMetaRepository : JpaRepository<AudioMetaEntity, UUID> {

    fun findAllByContentHashIsNullAndStorageUriIsNotNull(): List<AudioMetaEntity>

    @Modifying
    @Transactional
    @Query("UPDATE AudioMetaEntity a SET a.contentHash = :hash WHERE a.id = :id")
    fun updateContentHash(@Param("id") id: UUID, @Param("hash") hash: String)
}
