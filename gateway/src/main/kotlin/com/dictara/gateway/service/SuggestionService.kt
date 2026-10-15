package com.dictara.gateway.service

import com.dictara.gateway.port.Analysis
import com.fasterxml.jackson.annotation.JsonIgnore
import com.dictara.gateway.port.LibraryHint
import com.dictara.gateway.repository.SpeakerRepository
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.repository.TagRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.dictara.gateway.repository.TranscriptSegmentQueries
import com.dictara.gateway.repository.TranscriptSpeakerQueries
import com.dictara.gateway.repository.TranscriptSuggestionQueries
import com.dictara.gateway.util.NameColors
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.Locale
import java.util.UUID

/** A proposed speaker for a voice. The library speaker exists ([speakerId] set) or is created when this is accepted. */
data class SpeakerSuggestion(val voice: String, val name: String, val speakerId: UUID?, val evidence: String?) {
    val color: String get() = NameColors.of(name)
}

/** What the AI proposes for a transcription and the user has not settled yet. */
data class Suggestions(val title: String?, val tags: List<TagRef>, val speakers: List<SpeakerSuggestion>) {
    @get:JsonIgnore
    val isEmpty: Boolean get() = title == null && tags.isEmpty() && speakers.isEmpty()
}

/**
 * The AI's proposals: a title, tags from the user's library and who a voice is.
 *
 * What the model says is only a proposal, and it is checked before it is kept ([record]):
 *  - a tag has to be one of the user's tags;
 *  - a voice has to exist in the transcript, and its name has to be a valid speaker name;
 *  - the quote the model gives ("evidence") has to be in the transcript;
 *  - a name that is not in the speaker library has to be written in the transcript itself.
 * Whatever fails is left out. Nothing changes in the user's data until they accept: a speaker is not put in
 * the library until then, and rejecting leaves no trace besides the row that says it was rejected.
 */
@Service
class SuggestionService(
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val tagRepository: TagRepository,
    private val speakerRepository: SpeakerRepository,
    private val segmentQueries: TranscriptSegmentQueries,
    private val speakerQueries: TranscriptSpeakerQueries,
    private val queries: TranscriptSuggestionQueries,
    private val tagService: TagService,
    private val transcriptSpeakers: TranscriptSpeakerService,
) {
    companion object {
        const val MAX_TAGS = 5
        const val MAX_EVIDENCE_LENGTH = 200
        const val MAX_TITLE_LENGTH = 200

        /** How many library entries of each kind the model is told about. */
        const val MAX_LIBRARY_HINTS = 200

        /** Lower case, everything but letters and digits made a single space: "Hi, I'm Anna!" → "hi i m anna". */
        /** A title as it is kept: no quotes around it, one space between words. */
        private fun cleanTitle(raw: String): String = raw.trim().trim('"', '\'', '«', '»', '“', '”').replace(Regex("\\s+"), " ")

        /**
         * A quote as the person said it. The transcript the model reads has a marker in front of every phrase
         * (`[0:12:03 --> 0:12:09] [SPEAKER_02] ...`) and a model that copies a line copies the markers too; they are
         * not part of the words, so the check would fail on a quote that is really there.
         */
        private fun withoutMarkers(quote: String): String =
            quote.replace(Regex("\\[[^\\]]*]"), " ").replace(Regex("\\s+"), " ").trim()

        private fun normalize(text: String): String =
            text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        private val log = LoggerFactory.getLogger(SuggestionService::class.java)
    }

    data class Library(val tags: List<LibraryHint>, val speakers: List<LibraryHint>)

    /** What the model is told about the user's libraries when it is asked to analyse a transcription. */
    @Transactional(readOnly = true)
    fun libraryOf(submissionId: UUID): Library {
        val userId = submissionRepo.findById(submissionId).orElse(null)?.userId ?: return Library(emptyList(), emptyList())
        return Library(
            tags = tagRepository.findByUserIdOrderByCreatedAtAscNameAsc(userId).take(MAX_LIBRARY_HINTS).map { LibraryHint(it.name, it.description) },
            speakers = speakerRepository.findByUserId(userId).sortedBy { it.createdAt }.take(MAX_LIBRARY_HINTS).map { LibraryHint(it.name, it.description) },
        )
    }

    /** Keeps the proposals of [analysis] that hold up against the transcript and the libraries; the rest are dropped. */
    @Transactional
    fun record(submissionId: UUID, analysis: Analysis) {
        val submission = submissionRepo.findById(submissionId).orElse(null) ?: return
        val transcript = transcriptRepo.findBySubmissionId(submissionId) ?: return
        val transcriptId = transcript.id!!
        val userId = submission.userId
        val phrases = segmentQueries.findByTranscriptId(transcriptId)
        val said = " ${normalize(phrases.joinToString(" ") { it.text })} "
        val voices = phrases.mapNotNull { it.speaker }.toSet()

        val title = analysis.title?.trim()?.trim('"', '\'', '«', '»', '“', '”')?.replace(Regex("\\s+"), " ")
        if (!title.isNullOrEmpty() && title.length <= MAX_TITLE_LENGTH && title.none { it.isISOControl() } &&
            !title.equals(transcript.title, ignoreCase = true)
        ) {
            queries.insertTitle(transcriptId, title)
        }

        val tagIds = LinkedHashSet<UUID>()
        for (raw in analysis.tags) {
            val label = runCatching { TagService.normalizeLabel(raw) }.getOrNull() ?: continue
            val tag = tagRepository.findByUserIdAndName(userId, label)
            if (tag == null) { log.info("Suggestion dropped for $submissionId: tag '$label' is not in the library"); continue }
            if (tagIds.size < MAX_TAGS) tagIds += tag.id!!
        }
        tagIds.forEach { queries.insertTag(transcriptId, it) }

        val usedVoices = HashSet<String>()
        val usedNames = HashSet<String>()
        for (guess in analysis.speakers) {
            val name = runCatching { SpeakerService.normalizeName(guess.name) }.getOrNull() ?: continue
            val evidence = guess.evidence?.let(::withoutMarkers)?.takeIf { it.isNotEmpty() }
            if (guess.voice !in voices) {
                log.info("Suggestion dropped for $submissionId: there is no voice ${guess.voice}")
                continue
            }
            if (evidence == null || " ${normalize(evidence)} " !in said) {
                log.info("Suggestion dropped for $submissionId: the quote for ${guess.voice} is not in the transcript: '${guess.evidence?.take(120)}'")
                continue
            }
            val known = speakerRepository.findByUserIdAndNameIgnoringCase(userId, name)
            if (known == null && " ${normalize(name)} " !in said) {
                log.info("Suggestion dropped for $submissionId: '$name' is not in the library and not in the transcript")
                continue
            }
            if (guess.voice in usedVoices || !usedNames.add((known?.name ?: name).lowercase(Locale.ROOT))) continue
            usedVoices += guess.voice
            queries.insertSpeaker(transcriptId, guess.voice, known?.name ?: name, known?.id, evidence.take(MAX_EVIDENCE_LENGTH))
        }
    }

    /**
     * A new analysis of a transcription that has been analysed before ("regenerate summary"): what the user has
     * not settled is replaced by the new proposals, and so is the title; tags and speakers the user accepted or
     * rejected stay as they are, so a rejected one is not proposed again.
     */
    @Transactional
    fun refresh(submissionId: UUID, analysis: Analysis) {
        val transcriptId = transcriptRepo.findBySubmissionId(submissionId)?.id ?: return
        // A title the user turned down is not offered again; a different one is (there is one title row, so it is replaced).
        val rejected = queries.rejectedTitle(transcriptId)
        queries.deleteUnsettledAndTitle(transcriptId)
        val title = analysis.title?.takeUnless { rejected != null && cleanTitle(it).equals(rejected, ignoreCase = true) }
        record(submissionId, analysis.copy(title = title))
    }

    /** What is still proposed for the transcript, or null when nothing is. */
    @Transactional(readOnly = true)
    fun pending(transcriptId: UUID): Suggestions? {
        val suggestions = Suggestions(
            title = queries.pendingTitle(transcriptId),
            tags = queries.pendingTags(transcriptId).map { TagRef(it.tagId, it.name) },
            speakers = queries.pendingSpeakers(transcriptId).map { SpeakerSuggestion(it.voice, it.name, it.speakerId, it.evidence) },
        )
        return suggestions.takeUnless { it.isEmpty }
    }

    @Transactional
    fun acceptTag(userId: UUID, submissionId: UUID, label: String): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        settleTag(userId, submissionId, transcriptId, label, accept = true)
        return pending(transcriptId)
    }

    @Transactional
    fun rejectTag(userId: UUID, submissionId: UUID, label: String): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        settleTag(userId, submissionId, transcriptId, label, accept = false)
        return pending(transcriptId)
    }

    @Transactional
    fun acceptSpeaker(userId: UUID, submissionId: UUID, voice: String): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        settleSpeaker(userId, submissionId, transcriptId, voice, accept = true)
        return pending(transcriptId)
    }

    @Transactional
    fun rejectSpeaker(userId: UUID, submissionId: UUID, voice: String): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        settleSpeaker(userId, submissionId, transcriptId, voice, accept = false)
        return pending(transcriptId)
    }

    /** Accepts everything that is still proposed: the title, every tag and every speaker. */
    @Transactional
    fun acceptAll(userId: UUID, submissionId: UUID): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        queries.pendingTitle(transcriptId)?.let {
            transcriptRepo.updateTitle(transcriptId, it)
            queries.resolveTitle(transcriptId, ACCEPTED)
        }
        queries.pendingTags(transcriptId).forEach { settleTag(userId, submissionId, transcriptId, it.name, accept = true) }
        queries.pendingSpeakers(transcriptId).forEach { settleSpeaker(userId, submissionId, transcriptId, it.voice, accept = true) }
        queries.resolveAll(transcriptId, ACCEPTED) // proposals nobody sees any more (a tag already on it) are settled too
        return pending(transcriptId)
    }

    @Transactional
    fun dismissAll(userId: UUID, submissionId: UUID): Suggestions? {
        val transcriptId = ownTranscriptId(userId, submissionId)
        queries.resolveAll(transcriptId, REJECTED)
        return pending(transcriptId)
    }

    private fun settleTag(userId: UUID, submissionId: UUID, transcriptId: UUID, label: String, accept: Boolean) {
        val tag = runCatching { TagService.normalizeLabel(label) }.getOrNull()?.let { tagRepository.findByUserIdAndName(userId, it) }
            ?: throw noSuchSuggestion()
        if (!queries.resolveTag(transcriptId, tag.id!!, if (accept) ACCEPTED else REJECTED)) throw noSuchSuggestion()
        if (accept) tagService.attach(userId, submissionId, tag.id, null, null)
    }

    /** Accepting settles the voice as the proposed speaker in this transcript (see [TranscriptSpeakerService]), creating them in the library if they are new. */
    private fun settleSpeaker(userId: UUID, submissionId: UUID, transcriptId: UUID, voice: String, accept: Boolean) {
        val proposal = queries.pendingSpeakers(transcriptId).firstOrNull { it.voice == voice } ?: throw noSuchSuggestion()
        if (accept) {
            val phrases = speakerQueries.phrases(transcriptId).filter { it.voice == voice }.map { it.idx }.toSet()
            transcriptSpeakers.assign(
                userId, submissionId, TranscriptSpeakerService.Scope.ALL_BY_SPEAKER, phrases,
                proposal.speakerId, if (proposal.speakerId == null) proposal.name else null,
            )
        }
        queries.resolveSpeaker(transcriptId, voice, if (accept) ACCEPTED else REJECTED)
    }

    /** The transcript of the user's submission; 404 for someone else's or a missing one, 409 while there is no transcript. */
    private fun ownTranscriptId(userId: UUID, submissionId: UUID): UUID {
        val submission = submissionRepo.findById(submissionId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        return transcriptRepo.findBySubmissionId(submission.id!!)?.id
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "The transcript is not ready yet")
    }

    private fun noSuchSuggestion() = ResponseStatusException(HttpStatus.NOT_FOUND, "No such suggestion")
}

private const val ACCEPTED = "accepted"
private const val REJECTED = "rejected"
