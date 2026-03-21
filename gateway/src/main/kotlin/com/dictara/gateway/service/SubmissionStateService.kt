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
    /** Claims the next pending submission (oldest by createdAt), marks it processing, and returns it.
     *  Returns null if no pending submissions exist or another job is already processing.
     *  The partial unique index on status='processing' enforces at most one at a time. */
    @Transactional
    fun claimNextPendingSubmission(): SubmissionEntity? {
        if (submissionRepo.existsByStatus("processing")) return null
        val submission = submissionRepo.findNextPendingForUpdate() ?: return null
        submission.status = "processing"
        submission.updatedAt = Instant.now()
        submissionRepo.save(submission)
        return submission
    }

    /** Picks up in-flight stage_attempts for crash recovery. */
    @Transactional
    fun claimInFlightAttempts(): List<StageAttemptEntity> {
        val inFlight = stageAttemptRepo.findInFlightForUpdate()
        inFlight.forEach { it.status = "resuming" }
        stageAttemptRepo.saveAll(inFlight)
        return inFlight
    }

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

    /** Transitions a submission to 'summarizing' (after transcription completes). */
    @Transactional
    fun startSummarizing(submissionId: UUID) {
        val submission = submissionRepo.findById(submissionId).orElseThrow()
        submission.status = "summarizing"
        submission.updatedAt = Instant.now()
        submissionRepo.save(submission)
    }

    /** Resets an incompletely-claimed submission back to 'pending' (gateway crashed before transcriber submit).
     *  Marks the dangling stage_attempt as failed so it won't be picked up again. */
    @Transactional
    fun resetToQueue(attempt: StageAttemptEntity) {
        val submission = submissionRepo.findById(attempt.submissionId).orElseThrow()
        submission.status = "pending"
        submission.updatedAt = Instant.now()
        submissionRepo.save(submission)
        attempt.status = "failed"
        attempt.error = "Incomplete claim — reset on restart"
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
