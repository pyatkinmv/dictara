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
    private val speakerQueries: TranscriptSpeakerQueries,
    private val suggestionService: SuggestionService,
    private val audioRestoreService: AudioRestoreService,
    private val regenerationService: SummaryRegenerationService,
    private val recordedAt: RecordedAt,
) {
    data class CreateResult(val submissionId: UUID, val dedup: Boolean)

    data class SubmissionSummary(
        val jobId: UUID,
        val fileName: String,
        val title: String,
        val createdAt: String,
        /** When the recording was made, if the file says (the container's time or a date in its name); else null. */
        val recordedAt: String? = null,
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

    /** [speaker] is the diarizer's label; [assignedSpeaker] the library speaker set on this phrase alone, if any. */
    data class DetailSegment(val start: Double, val end: Double, val text: String, val speaker: String?, val assignedSpeaker: SpeakerRef? = null)

    /** In this transcript the diarizer voice [voice] is the library speaker [speaker]. */
    data class VoiceAssignment(val voice: String, val speaker: SpeakerRef)

    /** Everything the detail page of one transcription shows (`GET /transcriptions/{id}`). */
    data class TranscriptionDetail(
        val jobId: UUID,
        val fileName: String,
        val title: String,
        val fileSizeBytes: Long,
        val createdAt: String,
        /** When the recording was made, if the file says; null otherwise. */
        val recordedAt: String? = null,
        val status: String,
        val audioDurationS: Double?,
        val language: String?,
        val wordCount: Int,
        val tags: List<TagRef>,
        /** The summary as the summarizer wrote it (Markdown), or null when there is none. */
        val summary: String?,
        /** Empty until the transcript exists; a segment's speaker is null when the audio was not diarized. */
        val segments: List<DetailSegment>,
        /** Which speaker each named voice is; a voice not listed is still "Speaker N". */
        val voices: List<VoiceAssignment>,
        /** What the AI proposes and the user has not settled; left out when there is nothing. */
        val suggestions: Suggestions? = null,
        /** The state of "regenerate summary": available, running, used (with `at`) or unavailable. */
        val summaryRegeneration: SummaryRegenerationService.Regeneration? = null,
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
            // A re-send of a file whose stored copy is gone only brings the audio back (for every record with this
            // content) and answers with the transcription that exists, whatever options came with it.
            lockForDedup("audio:$contentHash")
            audioRestoreService.adoptOriginalName(userId, contentHash, originalName)
            audioRestoreService.restore(userId, contentHash, uploadResult.ref.uri)?.let { return CreateResult(it, dedup = true) }

            lockForDedup(listOf(userId, contentHash, model, languageHint, diarize, numSpeakers ?: 0, summaryMode).joinToString(":"))

            val duplicate = submissionRepo.findDuplicate(userId, contentHash, model, languageHint, diarize, numSpeakers, summaryMode)
            if (duplicate != null) return CreateResult(duplicate.id!!, dedup = true)
        }

        planService.enforce(userId)

        val audio = audioMetaRepo.save(AudioMetaEntity(
            id = audioId, userId = userId, originalName = originalName,
            contentType = contentType, sizeBytes = sizeBytes,
            storageUri = uploadResult.ref.uri, contentHash = contentHash,
        ).apply { _isNew = true })
        recordedAt.setFromFileName(audio.id, originalName)
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

    /** Blocks (not just checks) until any other in-flight request with the same [key] has committed — otherwise the
     *  check and the INSERT in [createSubmission] race it (see tryLockForDedup's doc). The holder does a SELECT + INSERT
     *  and, for the `audio:` key, asks storage whether the old objects still exist, so when dozens of copies of one
     *  file arrive at once (a user forwarding a chat to the bot) the last ones wait their turn: a minute is allowed
     *  ([DEDUP_LOCK_MAX_ATTEMPTS] tries 20 ms apart); the cap just turns a stuck holder into a clear error instead of
     *  a hang. */
    private fun lockForDedup(key: String) {
        var attempts = 0
        while (!submissionRepo.tryLockForDedup(key)) {
            if (++attempts > DEDUP_LOCK_MAX_ATTEMPTS) throw IllegalStateException("Timed out waiting for the dedup lock ($key)")
            Thread.sleep(20)
        }
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

    /** One entry of the list, as the list would show it now (a freshly accepted upload, or the one it duplicates). */
    @Transactional(readOnly = true)
    fun listItem(submissionId: UUID): SubmissionSummary? = summarize(inOrder(listOf(submissionId))).singleOrNull()

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
                recordedAt = audio?.recordedAt?.toString(),
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
        val segments = transcript?.id?.let { speakerQueries.detailPhrases(it) }?.map {
            DetailSegment(
                start = it.start, end = it.end, text = it.text, speaker = it.voice,
                assignedSpeaker = it.own?.let { own -> SpeakerRef(own.id, own.name) },
            )
        } ?: emptyList()
        val voices = transcript?.id?.let { speakerQueries.voices(it) }
            ?.map { VoiceAssignment(it.voice, SpeakerRef(it.speaker.id, it.speaker.name)) } ?: emptyList()
        val fileName = audio?.originalName ?: "unknown"
        return TranscriptionDetail(
            jobId = submissionId,
            fileName = fileName,
            title = transcript?.title?.takeIf { it.isNotBlank() } ?: fileName,
            fileSizeBytes = audio?.sizeBytes ?: 0,
            createdAt = submission.createdAt.toString(),
            recordedAt = audio?.recordedAt?.toString(),
            status = submission.status,
            audioDurationS = audio?.durationS,
            language = transcript?.detectedLanguage,
            wordCount = segments.sumOf { seg -> seg.text.split(Regex("\\s+")).count { it.isNotEmpty() } },
            tags = transcript?.id?.let { tagRepository.findByTranscriptId(it).map { tag -> TagRef(tag.id!!, tag.name) } } ?: emptyList(),
            summary = summaryRepo.findBySubmissionId(submissionId)?.text?.takeIf { it.isNotBlank() },
            segments = segments,
            voices = voices,
            suggestions = transcript?.id?.let { suggestionService.pending(it) },
            summaryRegeneration = regenerationService.stateOf(userId, submissionId),
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
                transcriptText = transcriptRepo.findBySubmissionId(id)?.id?.let { resolvedTranscriptText(it) },
                summaryText = summaryRepo.findBySubmissionId(id)?.text,
                originalName = audio?.originalName,
                storageUri = audio?.storageUri,
            )
        }
    }

    /**
     * The `.txt` download, Copy, and the ZIP export: each phrase with who actually said it, matching what the
     * page shows (a per-phrase mark, else its voice's confirmed speaker, else "Speaker N") — never the raw
     * diarizer label ("SPEAKER_00"), which [com.dictara.gateway.util.TranscriptFormatter.format] alone gives.
     * Empty when the transcript has no phrases (same as [TranscriptFormatter.format] on an empty list).
     */
    @Transactional(readOnly = true)
    fun resolvedTranscriptText(transcriptId: UUID): String {
        val phrases = speakerQueries.detailPhrases(transcriptId)
        val voiceSpeakers = speakerQueries.voices(transcriptId).associate { it.voice to it.speaker.name }
        return TranscriptFormatter.formatResolved(phrases.map { p ->
            TranscriptFormatter.ResolvedPhrase(
                start = p.start, end = p.end, text = p.text,
                speakerName = p.own?.name ?: p.voice?.let { voiceSpeakers[it] ?: fallbackVoiceName(it) },
            )
        })
    }

    /** The diarizer names its voices `SPEAKER_00`, `SPEAKER_01`, ...; unassigned, the page calls them
     *  "Speaker 1", "Speaker 2", ... (1-based) — this mirrors that naming for the exported text. */
    private fun fallbackVoiceName(voice: String): String =
        voice.removePrefix("SPEAKER_").toIntOrNull()?.let { "Speaker ${it + 1}" } ?: voice

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

    private companion object {
        const val DEDUP_LOCK_MAX_ATTEMPTS = 3000
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
