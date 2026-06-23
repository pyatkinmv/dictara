package com.dictara.gateway.repository

import com.dictara.gateway.entity.SpeakerEntity
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface SpeakerRepository : CrudRepository<SpeakerEntity, UUID> {
    fun findByUserIdAndName(userId: UUID, name: String): SpeakerEntity?
    fun findByUserId(userId: UUID): List<SpeakerEntity>
}
