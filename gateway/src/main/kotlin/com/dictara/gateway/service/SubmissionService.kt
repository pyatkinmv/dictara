package com.dictara.gateway.service

import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.TagEntity
import com.dictara.gateway.entity.TelegramDeliveryEntity
import com.dictara.gateway.plan.PlanService
import com.dictara.gateway.repository.*
import com.dictara.gateway.storage.UploadResult
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class SubmissionService(
    private val submissionRepo: SubmissionRepository,
    private val audioMetaRepo: AudioMetaRepository,
    private val submissionTagRepo: SubmissionTagRepository,
    private val tagRepository: TagRepository,
    private val telegramDeliveryRepo: TelegramDeliveryRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
    private val orchestrator: OrchestratorService,
    private val planService: PlanService,
) {
    data class CreateResult(val submissionId: UUID, val dedup: Boolean)

    data class SubmissionSummary(
        val jobId: UUID,
        val fileName: String,
        val createdAt: String,
        val status: String,
        val tags: List<String>,
    )

    data class ExportData(
        val submissionId: UUID,
        val createdAt: Instant,
        val transcriptText: String?,
        val summaryText: String?,
        val originalName: String?,
        val storageUri: String?,
    )

    @Transactional
    fun createSubmission(
        userId: UUID,
        audioId: UUID,
        originalName: String,
        contentType: String,
        sizeBytes: Long,
        uploadResult: UploadResult,
        model: String,
        language: String,
        diarize: Boolean,
        numSpeakers: Int?,
        summaryMode: String,
        source: String,
        telegramChatId: Long?,
        telegramMessageId: Long?,
    ): CreateResult {
        val contentHash = uploadResult.contentHash
        if (contentHash.isNotEmpty()) {
            val duplicate = submissionRepo.findDuplicate(userId, contentHash, model, language, diarize, numSpeakers, summaryMode)
            if (duplicate != null) return CreateResult(duplicate.id!!, dedup = true)
        }

        planService.enforce(userId)

        val audio = audioMetaRepo.save(AudioMetaEntity(
            id = audioId, userId = userId, originalName = originalName,
            contentType = contentType, sizeBytes = sizeBytes,
            storageUri = uploadResult.ref.uri, contentHash = contentHash,
        ).apply { _isNew = true })
        val submission = submissionRepo.save(SubmissionEntity(
            userId = userId, audioId = audio.id, model = model, language = language,
            diarize = diarize, numSpeakers = numSpeakers, summaryMode = summaryMode, source = source,
        ))
        if (telegramChatId != null) {
            telegramDeliveryRepo.save(TelegramDeliveryEntity(
                jobId = submission.id!!, chatId = telegramChatId,
                telegramMessageId = telegramMessageId,
            ).apply { _isNew = true })
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() { orchestrator.signalPending() }
        })
        return CreateResult(submission.id!!, dedup = false)
    }

    @Transactional(readOnly = true)
    fun listForUser(userId: UUID): List<SubmissionSummary> {
        val submissions = submissionRepo.findByUserIdOrderByCreatedAtDesc(userId)
        val audioById = audioMetaRepo.findAllById(submissions.map { it.audioId }).associateBy { it.id }
        val submissionIds = submissions.mapNotNull { it.id }
        val tagsBySubmission: Map<UUID, List<String>> = if (submissionIds.isEmpty()) {
            emptyMap()
        } else {
            val submissionTags = submissionTagRepo.findBySubmissionIdIn(submissionIds)
            val tagsById = tagRepository.findAllById(submissionTags.map { it.tagId }).associateBy { it.id }
            submissionTags.groupBy({ it.submissionId }, { tagsById[it.tagId]?.name ?: "" })
        }
        return submissions.map { s ->
            SubmissionSummary(
                jobId = s.id!!,
                fileName = audioById[s.audioId]?.originalName ?: "unknown",
                createdAt = s.createdAt.toString(),
                status = s.status,
                tags = (tagsBySubmission[s.id] ?: emptyList()).sorted(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun loadExportData(userId: UUID): List<ExportData> {
        val submissions = submissionRepo.findByUserIdAndStatusOrderByCreatedAtAsc(userId, "done")
        val audioById = audioMetaRepo.findAllById(submissions.map { it.audioId }).associateBy { it.id }
        return submissions.map { s ->
            val id = s.id!!
            val audio = audioById[s.audioId]
            ExportData(
                submissionId = id,
                createdAt = s.createdAt,
                transcriptText = transcriptRepo.findBySubmissionId(id)?.formattedText,
                summaryText = summaryRepo.findBySubmissionId(id)?.text,
                originalName = audio?.originalName,
                storageUri = audio?.storageUri,
            )
        }
    }

    @Transactional
    fun addTag(submissionId: UUID, userId: UUID, tagName: String): List<String> {
        val submission = submissionRepo.findById(submissionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found") }
        if (submission.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val tag = tagRepository.findByUserIdAndName(userId, tagName)
            ?: tagRepository.save(TagEntity(userId = userId, name = tagName))
        if (!submissionTagRepo.existsBySubmissionIdAndTagId(submissionId, tag.id!!)) {
            submissionTagRepo.insert(submissionId, tag.id)
        }
        return tagRepository.findBySubmissionId(submissionId).map { it.name }
    }

    @Transactional
    fun removeTag(submissionId: UUID, userId: UUID, tagName: String): List<String> {
        val submission = submissionRepo.findById(submissionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found") }
        if (submission.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        tagRepository.findByUserIdAndName(userId, tagName)?.let { tag ->
            submissionTagRepo.deleteBySubmissionIdAndTagId(submissionId, tag.id!!)
        }
        return tagRepository.findBySubmissionId(submissionId).map { it.name }
    }

    @Transactional
    fun scheduleDeliveryFailure(jobId: UUID, retryAfterS: Long?) {
        val retryAfterTs = if (retryAfterS != null) {
            Instant.now().plusSeconds(retryAfterS)
        } else {
            val attempt = telegramDeliveryRepo.findById(jobId).map { it.attemptCount }.orElse(1)
            Instant.now().plusSeconds(minOf(30L shl (attempt - 1), 3600L))
        }
        telegramDeliveryRepo.scheduleRetry(jobId, retryAfterTs)
    }
}
