package com.dictara.gateway.repository

import com.dictara.gateway.entity.AudioContentEntity
import com.dictara.gateway.entity.AudioMetaEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AudioMetaRepository : JpaRepository<AudioMetaEntity, UUID>

interface AudioContentRepository : JpaRepository<AudioContentEntity, UUID>
