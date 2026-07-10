package com.dictara.gateway.repository

import com.dictara.gateway.entity.TranscriptTagEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface TranscriptTagRepository : Repository<TranscriptTagEntity, UUID> {

    @Query("SELECT * FROM transcript_tags WHERE transcript_id = :transcriptId")
    fun findByTranscriptId(transcriptId: UUID): List<TranscriptTagEntity>

    @Query("SELECT * FROM transcript_tags WHERE transcript_id IN (:transcriptIds)")
    fun findByTranscriptIdIn(transcriptIds: Collection<UUID>): List<TranscriptTagEntity>

    @Modifying
    @Query("INSERT INTO transcript_tags(transcript_id, tag_id) VALUES (:transcriptId, :tagId)")
    fun insert(transcriptId: UUID, tagId: UUID)

    @Modifying
    @Query("DELETE FROM transcript_tags WHERE transcript_id = :transcriptId AND tag_id = :tagId")
    fun deleteByTranscriptIdAndTagId(transcriptId: UUID, tagId: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM transcript_tags WHERE transcript_id = :transcriptId AND tag_id = :tagId)")
    fun existsByTranscriptIdAndTagId(transcriptId: UUID, tagId: UUID): Boolean
}
