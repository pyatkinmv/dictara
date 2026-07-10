package com.dictara.gateway.repository

import com.dictara.gateway.entity.TranscriptSpeakerEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface TranscriptSpeakerRepository : Repository<TranscriptSpeakerEntity, UUID> {

    @Query("SELECT * FROM transcript_speakers WHERE transcript_id = :transcriptId")
    fun findByTranscriptId(transcriptId: UUID): List<TranscriptSpeakerEntity>

    @Modifying
    @Query("INSERT INTO transcript_speakers(transcript_id, speaker_id) VALUES (:transcriptId, :speakerId)")
    fun insert(transcriptId: UUID, speakerId: UUID)

    @Modifying
    @Query("DELETE FROM transcript_speakers WHERE transcript_id = :transcriptId AND speaker_id = :speakerId")
    fun deleteByTranscriptIdAndSpeakerId(transcriptId: UUID, speakerId: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM transcript_speakers WHERE transcript_id = :transcriptId AND speaker_id = :speakerId)")
    fun existsByTranscriptIdAndSpeakerId(transcriptId: UUID, speakerId: UUID): Boolean
}
