package com.dictara.gateway.service

import com.dictara.gateway.entity.*
import com.dictara.gateway.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class SubmissionStateService(
    private val submissionRepo: SubmissionRepository,
    private val stageAttemptRepo: StageAttemptRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
) {
    /** Picks up pending submissions, marks them processing, and returns them. */
    @Transactional
    fun claimPendingSubmissions(): List<SubmissionEntity> {
        val pending = submissionRepo.findPendingForUpdate()
        pending.forEach {
            it.status = "processing"
            it.updatedAt = Instant.now()
        }
        submissionRepo.saveAll(pending)
        return pending
    }

    /** Picks up in-flight stage_attempts for crash recovery. */
    @Transactional
    fun claimInFlightAttempts(): List<StageAttemptEntity> =
        stageAttemptRepo.findInFlightForUpdate()

    /** Creates a new stage_attempt row for the given stage. */
    @Transactional
    fun createAttempt(submissionId: UUID, stage: String): StageAttemptEntity {
        val attemptNum = stageAttemptRepo.countBySubmissionIdAndStage(submissionId, stage).toInt() + 1
        return stageAttemptRepo.save(StageAttemptEntity(
            submissionId = submissionId,
            stage = stage,
            attemptNum = attemptNum,
            status = "processing",
            startedAt = Instant.now(),
        ))
    }

    /** Stores the external job ID on the attempt row (called after dispatch to transcriber). */
    @Transactional
    fun setAttemptExternalJobId(attemptId: UUID, externalJobId: String) {
        val attempt = stageAttemptRepo.findById(attemptId).orElseThrow()
        attempt.externalJobId = externalJobId
        stageAttemptRepo.save(attempt)
    }

    /** Marks an attempt done and saves the transcript. */
    @Transactional
    fun saveTranscriptAndCompleteAttempt(
        submissionId: UUID,
        attemptId: UUID,
        segmentsJson: String,
        formattedText: String,
        audioDurationS: Double?,
    ) {
        transcriptRepo.save(TranscriptEntity(
            submissionId = submissionId,
            segments = segmentsJson,
            formattedText = formattedText,
            audioDurationS = audioDurationS,
        ))
        val attempt = stageAttemptRepo.findById(attemptId).orElseThrow()
        attempt.status = "done"
        attempt.finishedAt = Instant.now()
        stageAttemptRepo.save(attempt)
    }

    /** Marks an attempt failed. */
    @Transactional
    fun failAttempt(attemptId: UUID, error: String?) {
        val attempt = stageAttemptRepo.findById(attemptId).orElseThrow()
        attempt.status = "failed"
        attempt.error = error
        attempt.finishedAt = Instant.now()
        stageAttemptRepo.save(attempt)
    }

    /** Marks an attempt done and saves the summary. */
    @Transactional
    fun saveSummaryAndCompleteAttempt(submissionId: UUID, attemptId: UUID, text: String) {
        summaryRepo.save(SummaryEntity(submissionId = submissionId, text = text))
        val attempt = stageAttemptRepo.findById(attemptId).orElseThrow()
        attempt.status = "done"
        attempt.finishedAt = Instant.now()
        stageAttemptRepo.save(attempt)
    }

    /** Marks a submission as done. */
    @Transactional
    fun completeSubmission(submissionId: UUID) {
        val submission = submissionRepo.findById(submissionId).orElseThrow()
        submission.status = "done"
        submission.updatedAt = Instant.now()
        submissionRepo.save(submission)
    }

    /** Marks a submission as failed. */
    @Transactional
    fun failSubmission(submissionId: UUID) {
        val submission = submissionRepo.findById(submissionId).orElseThrow()
        submission.status = "failed"
        submission.updatedAt = Instant.now()
        submissionRepo.save(submission)
    }

    /** Returns how many attempts exist for the given stage. */
    @Transactional(readOnly = true)
    fun countAttempts(submissionId: UUID, stage: String): Int =
        stageAttemptRepo.countBySubmissionIdAndStage(submissionId, stage).toInt()

    /** Loads a submission with its EAGER associations (safe to access on worker thread). */
    @Transactional(readOnly = true)
    fun loadSubmission(submissionId: UUID): SubmissionEntity =
        submissionRepo.findById(submissionId).orElseThrow { NoSuchElementException("Submission $submissionId not found") }
}
