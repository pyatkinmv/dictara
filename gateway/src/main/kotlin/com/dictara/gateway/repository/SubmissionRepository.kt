package com.dictara.gateway.repository

import com.dictara.gateway.entity.SubmissionEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.time.Instant
import java.util.UUID

interface SubmissionRepository : CrudRepository<SubmissionEntity, UUID> {

    fun findByUserIdAndStatusOrderByCreatedAtAsc(userId: UUID, status: String): List<SubmissionEntity>

    /** Serializes concurrent [findDuplicate]-then-insert calls that share the same dedup [key] (see
     *  `SubmissionService.createSubmission`): without this, two near-simultaneous uploads of identical content
     *  can both pass the "no existing duplicate" check before either commits, since nothing at the DB level
     *  enforces the dedup uniqueness (only a plain, non-unique index backs `audio_meta(user_id, content_hash)`
     *  — see V12). `pg_try_advisory_xact_lock` is a session-scoped lock auto-released at the end of the
     *  caller's transaction (commit or rollback); the caller retries on `false` until the other request's
     *  transaction (and thus its INSERT) has committed, so the retry always then sees the row via
     *  [findDuplicate] instead of racing it. */
    @Query("SELECT pg_try_advisory_xact_lock(hashtext(:key)::bigint)")
    fun tryLockForDedup(key: String): Boolean

    fun existsByStatus(status: String): Boolean

    @Query("""
        SELECT * FROM submissions
        WHERE status = 'pending'
        ORDER BY created_at
        LIMIT 1
        FOR UPDATE NOWAIT
    """)
    fun findNextPendingForUpdate(): SubmissionEntity?

    @Query("SELECT COUNT(*) FROM submissions WHERE status = 'pending' AND created_at < :createdAt")
    fun countPendingSubmissionsBefore(createdAt: Instant): Long

    @Query("SELECT COUNT(*) FROM submissions WHERE status = :status")
    fun countByStatus(status: String): Long

    @Query("""
        SELECT s.* FROM submissions s
        JOIN audio_meta a ON s.audio_id = a.id
        WHERE a.user_id = :userId
          AND a.content_hash = :contentHash
          AND s.model = :model
          AND s.language_hint = :languageHint
          AND s.diarize = :diarize
          AND COALESCE(s.num_speakers, 0) = COALESCE(:numSpeakers, 0)
          AND s.summary_mode = :summaryMode
          AND s.status <> 'failed'
        ORDER BY s.created_at DESC
        LIMIT 1
    """)
    fun findDuplicate(
        userId: UUID,
        contentHash: String,
        model: String,
        languageHint: String,
        diarize: Boolean,
        numSpeakers: Int?,
        summaryMode: String,
    ): SubmissionEntity?

    /** This month's submissions that count against the plan, including the ones the user has deleted since
     *  (`deleted_submissions`): deleting does not give the allowance back. */
    @Query("""
        SELECT (SELECT COUNT(*) FROM submissions
                WHERE user_id = :userId
                  AND created_at >= date_trunc('month', NOW() AT TIME ZONE 'UTC')
                  AND status <> 'failed')
             + (SELECT COUNT(*) FROM deleted_submissions
                WHERE user_id = :userId
                  AND created_at >= date_trunc('month', NOW() AT TIME ZONE 'UTC'))
    """)
    fun countCurrentMonthSubmissions(userId: UUID): Long

    /** The latest submission that is not failed on any of these audio files, if there is one. */
    @Query("SELECT * FROM submissions WHERE audio_id IN (:audioIds) AND status <> 'failed' ORDER BY created_at DESC LIMIT 1")
    fun findLatestNonFailedByAudioIds(audioIds: Collection<UUID>): SubmissionEntity?

    /** Submissions ever, not scoped to the current month — for a plan with a lifetime cap instead of a
     *  recurring monthly one (the anonymous "try it" plan). */
    @Query("""
        SELECT (SELECT COUNT(*) FROM submissions WHERE user_id = :userId AND status <> 'failed')
             + (SELECT COUNT(*) FROM deleted_submissions WHERE user_id = :userId)
    """)
    fun countAllSubmissions(userId: UUID): Long
}
