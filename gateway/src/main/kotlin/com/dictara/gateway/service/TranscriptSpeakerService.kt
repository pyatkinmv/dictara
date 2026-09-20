package com.dictara.gateway.service

import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.dictara.gateway.repository.TranscriptSpeakerQueries
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Setting who speaks in a transcript (the Speaker Picker). See [TranscriptSpeakerQueries] for the two layers.
 *
 * After either, a voice whose phrases all belong to one speaker is recorded as that speaker's voice ([settleUniformVoices]).
 *
 * Two ways to assign:
 *  - [Scope.SEGMENT]: the given phrases are said by the speaker. A phrase that the speaker already gets through
 *    its voice needs no mark of its own, so none is kept.
 *  - [Scope.ALL_BY_SPEAKER]: the given phrases are everything the user sees under one name and are all to become
 *    the speaker. A voice that is entirely among them is settled once and for all ("this voice is Maria" in this
 *    transcript) and the marks on its phrases are dropped; the other phrases get a mark each.
 */
@Service
class TranscriptSpeakerService(
    private val speakerService: SpeakerService,
    private val submissionRepo: SubmissionRepository,
    private val transcriptRepo: TranscriptRepository,
    private val queries: TranscriptSpeakerQueries,
) {
    enum class Scope(val param: String) {
        SEGMENT("segment"), ALL_BY_SPEAKER("all");

        companion object {
            fun parse(param: String?): Scope? = entries.firstOrNull { it.param == param }
        }
    }

    /**
     * Makes the phrases [indexes] of the transcription the speaker's: the library speaker [speakerId], or the one
     * called [name] (created when the library has none). Returns that speaker.
     */
    @Transactional
    fun assign(userId: UUID, submissionId: UUID, scope: Scope, indexes: Set<Int>, speakerId: UUID?, name: String?): SpeakerRef {
        val submission = submissionRepo.findById(submissionId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        val transcriptId = transcriptRepo.findBySubmissionId(submission.id!!)?.id
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "The transcript is not ready yet")
        if (indexes.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give at least one phrase")

        val phrases = queries.phrases(transcriptId)
        val known = phrases.map { it.idx }.toSet()
        val unknown = indexes - known
        if (unknown.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This transcript has no phrase ${unknown.min()}")
        }
        val speaker = when {
            speakerId != null -> speakerService.own(userId, speakerId)
            name != null -> speakerService.findOrCreate(userId, name)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give either speaker_id or name")
        }

        val voiceSpeakers = queries.voiceSpeakers(transcriptId).toMutableMap()
        val byMark = indexes.toMutableSet() // phrases that end up with a mark of their own
        if (scope == Scope.ALL_BY_SPEAKER) {
            val chosen = phrases.filter { it.idx in indexes }.groupBy { it.voice }
            for ((voice, inChosen) in chosen) {
                if (voice == null) continue
                if (inChosen.size == phrases.count { it.voice == voice }) {
                    queries.setVoiceSpeaker(transcriptId, voice, speaker.id)
                    queries.clearPhraseSpeakers(transcriptId, voice)
                    voiceSpeakers[voice] = speaker.id
                    byMark -= inChosen.map { it.idx }.toSet()
                }
            }
        }

        // A mark that only repeats what the voice already says is not written (and an old one is cleared).
        val phraseByIdx = phrases.associateBy { it.idx }
        val (redundant, needed) = byMark.partition { idx ->
            val voice = phraseByIdx.getValue(idx).voice
            voice != null && voiceSpeakers[voice] == speaker.id
        }
        queries.setPhraseSpeaker(transcriptId, redundant, null)
        queries.setPhraseSpeaker(transcriptId, needed, speaker.id)

        settleUniformVoices(transcriptId)
        return speaker
    }

    /**
     * The canonical form: a voice whose phrases all end up with the same speaker (through marks, its own
     * speaker, or a mix) is that speaker's voice, so it is recorded as such and its marks are dropped. Without
     * this, marking the phrases of a voice one by one would leave the voice "Speaker 2" in the header while
     * every one of its phrases says "Maria".
     */
    private fun settleUniformVoices(transcriptId: UUID) {
        val phrases = queries.phrases(transcriptId)
        val voiceSpeakers = queries.voiceSpeakers(transcriptId)
        for ((voice, ofVoice) in phrases.filter { it.voice != null }.groupBy { it.voice!! }) {
            val speakers = ofVoice.map { it.speakerId ?: voiceSpeakers[voice] }.toSet()
            val only = speakers.singleOrNull() ?: continue
            if (voiceSpeakers[voice] != only) queries.setVoiceSpeaker(transcriptId, voice, only)
            if (ofVoice.any { it.speakerId != null }) queries.clearPhraseSpeakers(transcriptId, voice)
        }
    }
}
