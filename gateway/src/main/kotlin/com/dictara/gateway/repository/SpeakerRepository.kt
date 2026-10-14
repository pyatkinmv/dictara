package com.dictara.gateway.repository

import com.dictara.gateway.entity.SpeakerEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface SpeakerRepository : CrudRepository<SpeakerEntity, UUID> {
    fun findByUserIdAndName(userId: UUID, name: String): SpeakerEntity?
    fun findByUserId(userId: UUID): List<SpeakerEntity>

    /** The user's speaker with this name, whatever the case (names are unique that way). */
    @Query("SELECT * FROM speakers WHERE user_id = :userId AND lower(name) = lower(:name)")
    fun findByUserIdAndNameIgnoringCase(userId: UUID, name: String): SpeakerEntity?

    /** Renames and/or re-describes one speaker; the caller has checked that it exists and whose it is. */
    @Modifying
    @Query("UPDATE speakers SET name = :name, description = :description, updated_at = now() WHERE id = :id")
    fun update(id: UUID, name: String, description: String?)
}
