package com.dictara.gateway.service

import com.dictara.gateway.entity.SummaryEntity
import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.plan.PlanService
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.repository.SummaryRegenerationQueries
import com.dictara.gateway.repository.SummaryRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.dictara.gateway.repository.TranscriptSegmentQueries
import com.dictara.gateway.repository.TranscriptSpeakerQueries
import com.dictara.gateway.util.TranscriptFormatter
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * "Regenerate summary": asks Gemini again about a finished transcription (the transcript itself is not touched) and
 * replaces its summary and its unsettled proposals (title, tags, speakers) with the new answer. How many times a
 * transcription may be regenerated is the user's plan (`regenerations_per_transcription`, see [PlanService.regenerationLimit]);
 * a run that fails is not counted.
 */
@Service
class SummaryRegenerationService(
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val summaryRepo: SummaryRepository,
    private val audioMetaRepo: AudioMetaRepository,
    private val segmentQueries: TranscriptSegmentQueries,
    private val speakerQueries: TranscriptSpeakerQueries,
    private val suggestionService: SuggestionService,
    private val summarizer: SummarizerPort,
    private val queries: SummaryRegenerationQueries,
    private val planService: PlanService,
    txManager: PlatformTransactionManager,
) {
    /**
     * What the detail of a transcription tells the client about the button. [state]: `available` (it can be done),
     * `running`, `used` (the plan's limit is reached), `unavailable` (no summary service, or the plan has none).
     * [used] is how many times it was done, [max] how many the plan allows (null: no limit), [at] when it was last done.
     */
    data class Regeneration(val state: String, val used: Int, val max: Int?, val at: String? = null)

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val executor = Executors.newFixedThreadPool(2)

    /** A run cut short by a restart would stay "running" forever. */
    @PostConstruct
    fun releaseInterrupted() {
        val n = queries.releaseAllRunning()
        if (n > 0) log.warn("Gave back $n summary regeneration(s) that were interrupted by a restart")
    }

    fun stateOf(userId: UUID, submissionId: UUID): Regeneration {
        val s = queries.stateOf(submissionId)
        val max = planService.regenerationLimit(userId)
        val state = when {
            s.running -> "running"
            !summarizer.isAvailable() || max == 0 -> "unavailable"
            max != null && s.count >= max -> "used"
            else -> "available"
        }
        return Regeneration(state, s.count, max, s.at?.toString())
    }

    /** Starts the regeneration of [userId]'s transcription in the background; 404 / 409 when it cannot be done. */
    fun start(userId: UUID, submissionId: UUID) {
        val submission = submissionRepo.findById(submissionId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        val transcriptId = transcriptRepo.findBySubmissionId(submissionId)?.id
        if (submission.status != "done" || transcriptId == null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The transcription is not ready yet")
        }
        if (!summarizer.isAvailable()) throw ResponseStatusException(HttpStatus.CONFLICT, "The summary service is not available")
        if (segmentQueries.findByTranscriptId(transcriptId).isEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "There is nothing to summarize")
        }
        val limit = planService.regenerationLimit(userId)
        if (limit == 0) throw ResponseStatusException(HttpStatus.CONFLICT, "Your plan does not include regenerating summaries")
        if (!queries.claim(submissionId, limit)) {
            // Either a run is going, or the plan's limit for this transcription is used up; say which.
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                if (queries.stateOf(submissionId).running) "The summary is being regenerated now"
                else "Your plan allows regenerating the summary of a recording $limit time${if (limit == 1) "" else "s"}, and that is used up",
            )
        }
        executor.submit { run(submissionId) }
    }

    private fun run(submissionId: UUID) {
        try {
            val analysis = askGemini(submissionId)
            tx.executeWithoutResult {
                replaceSummary(submissionId, analysis.summary)
                // The proposals are a bonus: a failure here must not cost the new summary, as on the first run.
                runCatching { suggestionService.refresh(submissionId, analysis) }
                    .onFailure { log.warn("Suggestions for submission $submissionId were not refreshed: ${it.message}") }
            }
            queries.finish(submissionId)
            log.info("Summary regenerated for submission $submissionId (${analysis.summary.length} chars)")
        } catch (e: Exception) {
            queries.release(submissionId)
            log.error("Summary regeneration failed for submission $submissionId: ${e.message}")
        }
    }

    /** As on the first run, an unusable answer (no summary, a summary cut off) is asked for again, up to three times. */
    private fun askGemini(submissionId: UUID): Analysis {
        val submission = submissionRepo.findById(submissionId).orElseThrow()
        val transcript = transcriptRepo.findBySubmissionId(submissionId) ?: error("No transcript for $submissionId")
        // What the user has settled goes in with the transcript: a voice they named (or a phrase they marked) shows as that
        // person, not as SPEAKER_xx, so the summary can name people and the model only has the rest left to guess.
        // A voice nobody named keeps its label: a proposal has to say which voice it is about.
        val phrases = speakerQueries.detailPhrases(transcript.id!!)
        val named = speakerQueries.voices(transcript.id!!).associate { it.voice to it.speaker.name }
        val text = TranscriptFormatter.formatResolved(
            phrases.map { p -> TranscriptFormatter.ResolvedPhrase(p.start, p.end, p.own?.name ?: p.voice?.let { named[it] ?: it }, p.text) },
        )
        val namesConfirmed = phrases.any { it.own != null || (it.voice != null && it.voice in named) }
        val library = suggestionService.libraryOf(submissionId)
        // The mode chosen at upload may have been "off"; the person asked for a summary now, so it is "auto" then.
        val mode = SummaryMode.fromString(submission.summaryMode).takeIf { it != SummaryMode.OFF } ?: SummaryMode.AUTO
        val input = AnalysisInput(
            text = text,
            audioDurationSeconds = audioMetaRepo.findById(submission.audioId).orElse(null)?.durationS,
            mode = mode,
            language = summaryLanguage(submission.languageHint, transcript.detectedLanguage, text),
            tags = library.tags,
            speakers = library.speakers,
            namesConfirmed = namesConfirmed,
        )
        var last: Exception? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return summarizer.analyze(input)
            } catch (e: Exception) {
                last = e
                log.warn("Summary regeneration attempt ${attempt + 1} failed for submission $submissionId: ${e.message}")
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_PAUSE_MS)
            }
        }
        throw last!!
    }

    private fun replaceSummary(submissionId: UUID, text: String) {
        val existing = summaryRepo.findBySubmissionId(submissionId)
        if (existing != null) {
            existing.text = text
            summaryRepo.save(existing)
        } else {
            summaryRepo.save(SummaryEntity(submissionId = submissionId, text = text))
        }
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_PAUSE_MS = 2000L
    }
}
