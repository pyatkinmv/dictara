package com.dictara.gateway.service

import com.dictara.gateway.entity.AudioMetaEntity
import com.dictara.gateway.entity.SubmissionEntity
import com.dictara.gateway.entity.TagEntity
import com.dictara.gateway.entity.TelegramDeliveryEntity
import com.dictara.gateway.util.TranscriptFormatter
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
    private val transcriptTagRepo: TranscriptTagRepository,
    private val tagRepository: TagRepository,
    private val telegramDeliveryRepo: TelegramDeliveryRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
    private val orchestrator: OrchestratorService,
    private val planService: PlanService,
    private val transcriptListQueries: TranscriptListQueries,
    private val submissionListQuery: SubmissionListQuery,
    private val segmentQueries: TranscriptSegmentQueries,
) {
    data class CreateResult(val submissionId: UUID, val dedup: Boolean)

    data class SubmissionSummary(
        val jobId: UUID,
        val fileName: String,
        val title: String,
        val createdAt: String,
        val status: String,
        val tags: List<TagRef>,
        /** Length of the audio; known once the transcriber has finished. */
        val audioDurationS: Double? = null,
        /** Distinct speaker labels in the transcript; null while there is no transcript. */
        val speakerCount: Int? = null,
        /** Percent of the audio transcribed so far; only while transcribing (see [processingProgress]). */
        val processingPct: Int? = null,
        /** `transcribing`, `diarizing` or `summarizing` while the job is being worked on. */
        val processingPhase: String? = null,
    )

    /** A page of [SubmissionSummary]; [hasNext] tells whether another page follows. */
    data class SubmissionPage(val items: List<SubmissionSummary>, val hasNext: Boolean)

    data class DetailSegment(val start: Double, val end: Double, val text: String, val speaker: String?)

    /** Everything the detail page of one transcription shows (`GET /transcriptions/{id}`). */
    data class TranscriptionDetail(
        val jobId: UUID,
        val fileName: String,
        val title: String,
        val fileSizeBytes: Long,
        val createdAt: String,
        val status: String,
        val audioDurationS: Double?,
        val language: String?,
        val wordCount: Int,
        val tags: List<TagRef>,
        /** The summary as the summarizer wrote it (Markdown), or null when there is none. */
        val summary: String?,
        /** Empty until the transcript exists; a segment's speaker is null when the audio was not diarized. */
        val segments: List<DetailSegment>,
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
        languageHint: String,
        diarize: Boolean,
        numSpeakers: Int?,
        summaryMode: String,
        source: String,
        telegramChatId: Long?,
        telegramMessageId: Long?,
    ): CreateResult {
        val contentHash = uploadResult.contentHash
        if (contentHash.isNotEmpty()) {
            val duplicate = submissionRepo.findDuplicate(userId, contentHash, model, languageHint, diarize, numSpeakers, summaryMode)
            if (duplicate != null) return CreateResult(duplicate.id!!, dedup = true)
        }

        planService.enforce(userId)

        val audio = audioMetaRepo.save(AudioMetaEntity(
            id = audioId, userId = userId, originalName = originalName,
            contentType = contentType, sizeBytes = sizeBytes,
            storageUri = uploadResult.ref.uri, contentHash = contentHash,
        ).apply { _isNew = true })
        val submission = submissionRepo.save(SubmissionEntity(
            userId = userId, audioId = audio.id, model = model, languageHint = languageHint,
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

    /** The whole list of the user that matches [filter], in the order [filter] asks for (newest first by default). */
    @Transactional(readOnly = true)
    fun listForUser(userId: UUID, filter: TranscriptionListFilter = TranscriptionListFilter()): List<SubmissionSummary> =
        summarize(inOrder(submissionListQuery.findIds(userId, filter).ids))

    /** One page of that list (`GET /transcriptions?page=&size=`), [page] counting from 0. */
    @Transactional(readOnly = true)
    fun listPageForUser(userId: UUID, filter: TranscriptionListFilter, page: Int, size: Int): SubmissionPage {
        val found = submissionListQuery.findIds(userId, filter, page, size)
        return SubmissionPage(summarize(inOrder(found.ids)), found.hasNext)
    }

    /** The submissions with these ids, in the order of [ids] (the database returns them in any order). */
    private fun inOrder(ids: List<UUID>): List<SubmissionEntity> {
        val byId = submissionRepo.findAllById(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** The list entries for [submissions]; their files, transcripts and tags are fetched in a few batched queries. */
    private fun summarize(submissions: List<SubmissionEntity>): List<SubmissionSummary> {
        val audioById = audioMetaRepo.findAllById(submissions.map { it.audioId }).associateBy { it.id }
        val submissionIds = submissions.mapNotNull { it.id }
        if (submissionIds.isEmpty()) return emptyList()

        // Not the transcripts themselves: their segments can be huge and the list only needs a few facts.
        val transcriptBySubmissionId = transcriptListQueries.findRows(submissionIds).associateBy { it.submissionId }
        val transcriptIds = transcriptBySubmissionId.values.map { it.transcriptId }

        val tagsByTranscriptId: Map<UUID, List<TagRef>> = if (transcriptIds.isEmpty()) {
            emptyMap()
        } else {
            val transcriptTags = transcriptTagRepo.findByTranscriptIdIn(transcriptIds)
            val tagsById = tagRepository.findAllById(transcriptTags.map { it.tagId }).associateBy { it.id }
            transcriptTags.groupBy({ it.transcriptId }) { tt -> tagsById[tt.tagId]?.let { TagRef(it.id!!, it.name) } }
                .mapValues { (_, refs) -> refs.filterNotNull() }
        }

        return submissions.map { s ->
            val audio = audioById[s.audioId]
            val fileName = audio?.originalName ?: "unknown"
            val transcript = transcriptBySubmissionId[s.id]
            val (phase, pct) = processingProgress(s.status, orchestrator.getLiveProgress(s.id!!))
            SubmissionSummary(
                jobId = s.id!!,
                fileName = fileName,
                title = transcript?.title ?: fileName,
                createdAt = s.createdAt.toString(),
                status = s.status,
                tags = (tagsByTranscriptId[transcript?.transcriptId] ?: emptyList()).sortedBy { it.label },
                audioDurationS = audio?.durationS,
                speakerCount = transcript?.speakerCount,
                processingPct = pct,
                processingPhase = phase,
            )
        }
    }

    /** One transcription of [userId] with its text, or null when there is none with this id (also someone else's). */
    @Transactional(readOnly = true)
    fun getDetail(userId: UUID, submissionId: UUID): TranscriptionDetail? {
        val submission = submissionRepo.findById(submissionId).orElse(null)?.takeIf { it.userId == userId } ?: return null
        val audio = audioMetaRepo.findById(submission.audioId).orElse(null)
        val transcript = transcriptRepo.findBySubmissionId(submissionId)
        val segments = transcript?.id?.let { segmentQueries.findByTranscriptId(it) }
            ?.map { DetailSegment(start = it.start, end = it.end, text = it.text, speaker = it.speaker) }
            ?: emptyList()
        val fileName = audio?.originalName ?: "unknown"
        return TranscriptionDetail(
            jobId = submissionId,
            fileName = fileName,
            title = transcript?.title?.takeIf { it.isNotBlank() } ?: fileName,
            fileSizeBytes = audio?.sizeBytes ?: 0,
            createdAt = submission.createdAt.toString(),
            status = submission.status,
            audioDurationS = audio?.durationS,
            language = transcript?.detectedLanguage,
            wordCount = segments.sumOf { seg -> seg.text.split(Regex("\\s+")).count { it.isNotEmpty() } },
            tags = transcript?.id?.let { tagRepository.findByTranscriptId(it).map { tag -> TagRef(tag.id!!, tag.name) } } ?: emptyList(),
            summary = summaryRepo.findBySubmissionId(submissionId)?.text?.takeIf { it.isNotBlank() },
            segments = segments,
        )
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
                transcriptText = transcriptRepo.findBySubmissionId(id)
                    ?.let { TranscriptFormatter.format(segmentQueries.findByTranscriptId(it.id!!)) },
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
        val transcript = transcriptRepo.findBySubmissionId(submissionId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Transcript not ready")
        val tag = tagRepository.findByUserIdAndName(userId, tagName)
            ?: tagRepository.save(TagEntity(userId = userId, name = tagName))
        if (!transcriptTagRepo.existsByTranscriptIdAndTagId(transcript.id!!, tag.id!!)) {
            transcriptTagRepo.insert(transcript.id, tag.id)
        }
        return tagRepository.findByTranscriptId(transcript.id).map { it.name }
    }

    @Transactional
    fun removeTag(submissionId: UUID, userId: UUID, tagName: String): List<String> {
        val submission = submissionRepo.findById(submissionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found") }
        if (submission.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val transcript = transcriptRepo.findBySubmissionId(submissionId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Transcript not ready")
        tagRepository.findByUserIdAndName(userId, tagName)?.let { tag ->
            transcriptTagRepo.deleteByTranscriptIdAndTagId(transcript.id!!, tag.id!!)
        }
        return tagRepository.findByTranscriptId(transcript.id!!).map { it.name }
    }

    @Transactional
    fun updateTitle(submissionId: UUID, userId: UUID, title: String?) {
        val submission = submissionRepo.findById(submissionId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found") }
        if (submission.userId != userId) throw ResponseStatusException(HttpStatus.FORBIDDEN)
        val transcript = transcriptRepo.findBySubmissionId(submissionId)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Transcript not ready")
        transcriptRepo.updateTitle(transcript.id!!, title?.takeIf { it.isNotBlank() })
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

/**
 * What the list shows of a job that is being worked on: the phase and, while transcribing, how far it is.
 *
 * The percentage exists for transcription only. Diarization and summarizing are shown by name alone (the
 * client fills the bar), so nothing is invented for phases whose share of the total time is unknown.
 * Without live progress (the gateway restarted meanwhile, or the job has just been picked up) a job that
 * is `processing` has no phase either.
 */
internal fun processingProgress(status: String, live: com.dictara.gateway.dto.ProgressInfo?): Pair<String?, Int?> = when (status) {
    "summarizing" -> "summarizing" to null
    "processing" -> when {
        live == null -> null to null
        live.phase == "diarizing" -> "diarizing" to null
        else -> {
            val total = live.totalS
            val done = live.processedS
            val pct = if (total != null && total > 0 && done != null) (done / total * 100).toInt().coerceIn(0, 100) else null
            "transcribing" to pct
        }
    }
    else -> null to null
}
