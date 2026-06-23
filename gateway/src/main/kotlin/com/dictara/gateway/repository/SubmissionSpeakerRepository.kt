package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionSpeakerEntity
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface SubmissionSpeakerRepository : Repository<SubmissionSpeakerEntity, UUID> {

    @Query("SELECT * FROM submission_speakers WHERE submission_id = :submissionId")
    fun findBySubmissionId(submissionId: UUID): List<SubmissionSpeakerEntity>

    @Modifying
    @Query("INSERT INTO submission_speakers(submission_id, speaker_id) VALUES (:submissionId, :speakerId)")
    fun insert(submissionId: UUID, speakerId: UUID)

    @Modifying
    @Query("DELETE FROM submission_speakers WHERE submission_id = :submissionId AND speaker_id = :speakerId")
    fun deleteBySubmissionIdAndSpeakerId(submissionId: UUID, speakerId: UUID)

    @Query("SELECT EXISTS(SELECT 1 FROM submission_speakers WHERE submission_id = :submissionId AND speaker_id = :speakerId)")
    fun existsBySubmissionIdAndSpeakerId(submissionId: UUID, speakerId: UUID): Boolean
}
