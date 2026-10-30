package com.dictara.gateway.service

import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Deletes a transcription for good: the record with its transcript, phrases, summary, tags, speaker marks and
 * suggestions (all of them go with the submission through `ON DELETE CASCADE`), and the audio file, unless something
 * else still uses it.
 *
 * Audio is shared: one stored file serves every record with the same content (any user, any options), so a record's
 * audio row is removed only when no other record points at it, and the object in storage only when no audio row points
 * at it any more. What stays behind is `deleted_submissions`, one row of ids and a date, so that the plan's allowance is
 * not given back.
 */
@Service
class TranscriptionDeletionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val submissionRepo: SubmissionRepository,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Target(
        val status: String,
        val audioId: UUID,
        val createdAt: Instant,
        val regenerating: Boolean,
        val storageUri: String?,
        val contentHash: String?,
    )

    /**
     * 404 when the transcription does not exist or is someone else's; 409 while it is being processed or its summary is
     * being regenerated (deleting then would pull the data from under the running work).
     */
    @Transactional
    fun delete(userId: UUID, submissionId: UUID) {
        val target = jdbc.query(
            """
            SELECT s.status, s.audio_id, s.created_at, (s.summary_regeneration = 'running') AS regenerating,
                   a.storage_uri, a.content_hash
            FROM submissions s JOIN audio_meta a ON a.id = s.audio_id
            WHERE s.id = :id AND s.user_id = :user
            FOR UPDATE OF s
            """.trimIndent(),
            mapOf("id" to submissionId, "user" to userId),
        ) { rs, _ ->
            Target(
                status = rs.getString("status"),
                audioId = rs.getObject("audio_id", UUID::class.java),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                regenerating = rs.getBoolean("regenerating"),
                storageUri = rs.getString("storage_uri"),
                contentHash = rs.getString("content_hash"),
            )
        }.firstOrNull() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")

        if (target.status !in FINISHED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This transcription is still being processed. Try again when it is done.")
        }
        if (target.regenerating) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The summary is being regenerated. Try again in a minute.")
        }

        // The same lock a new upload of this content takes, so that an upload cannot point a new record at the file
        // between our check that nothing uses it and its removal.
        target.contentHash?.takeIf { it.isNotEmpty() }?.let { lockForAudio("audio:$it") }

        if (target.status != "failed") {
            // A failed one is not counted by the plan, so it is not remembered either.
            jdbc.update(
                "INSERT INTO deleted_submissions (id, user_id, created_at) VALUES (:id, :user, :created)",
                mapOf("id" to submissionId, "user" to userId, "created" to java.sql.Timestamp.from(target.createdAt)),
            )
        }
        jdbc.update("DELETE FROM submissions WHERE id = :id", mapOf("id" to submissionId))

        val stillUsedByRecords = jdbc.queryForObject(
            "SELECT count(*) FROM submissions WHERE audio_id = :audio", mapOf("audio" to target.audioId), Int::class.java,
        ) ?: 0
        if (stillUsedByRecords > 0) return
        jdbc.update("DELETE FROM audio_meta WHERE id = :audio", mapOf("audio" to target.audioId))

        val uri = target.storageUri ?: return
        val stillUsedByUri = jdbc.queryForObject(
            "SELECT count(*) FROM audio_meta WHERE storage_uri = :uri", mapOf("uri" to uri), Int::class.java,
        ) ?: 0
        if (stillUsedByUri == 0) {
            // Inside the transaction on purpose: if the storage refuses, the rows come back and the user can try again,
            // instead of a record that is gone while its file stays for good. (The weekly orphan cleanup is the net
            // for the rare case that the commit fails after the file is gone.)
            audioStorage.delete(AudioRef(uri))
            log.info("Deleted transcription {} and its audio {}", submissionId, uri)
        } else {
            log.info("Deleted transcription {}; its audio {} stays, other records use it", submissionId, uri)
        }
    }

    private fun lockForAudio(key: String) {
        var attempts = 0
        while (!submissionRepo.tryLockForDedup(key)) {
            if (++attempts > LOCK_MAX_ATTEMPTS) throw ResponseStatusException(HttpStatus.CONFLICT, "Busy, try again in a moment.")
            Thread.sleep(20)
        }
    }

    companion object {
        private val FINISHED = setOf("done", "failed")
        private const val LOCK_MAX_ATTEMPTS = 500
    }
}
