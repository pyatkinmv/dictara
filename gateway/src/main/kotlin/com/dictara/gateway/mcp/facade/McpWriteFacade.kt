package com.dictara.gateway.mcp.facade

import com.dictara.gateway.service.SpeakerService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.SuggestionService
import com.dictara.gateway.service.Suggestions
import com.dictara.gateway.service.SummaryTextService
import com.dictara.gateway.service.TagService
import com.dictara.gateway.service.TranscriptSpeakerService
import com.dictara.gateway.service.TranscriptTextService
import org.springframework.stereotype.Component
import java.util.UUID

data class McpDeleted(val deleted: String, val affectedTranscriptions: Int)
data class McpRenamed(val id: UUID, val title: String)
data class McpSummaryChange(val id: UUID, val before: String?, val after: String)
data class McpPhraseEdit(val index: Int, val before: String, val after: String)
data class McpAssigned(val id: UUID, val speaker: String, val phrases: Int)
data class McpTagOnTranscription(val id: UUID, val tag: McpTagBrief, val onTranscription: Boolean)
data class McpSettled(val id: UUID, val left: McpSuggestions?)

/** The kinds of proposal the AI makes. */
enum class SuggestionKind(val param: String) {
    TITLE("title"), TAG("tag"), SPEAKER("speaker");

    companion object {
        fun parse(param: String?): SuggestionKind? = entries.firstOrNull { it.param == param }
    }
}

/**
 * What the MCP tools may change, and the only thing a write tool may call. Like [McpReadFacade] it goes through the
 * services of the app (so the rules about names, lengths and whose data it is are the app's own) and takes the user
 * from the token only. It has nothing that costs money: no summarizer, no transcriber, no storage; a summary written
 * here is text stored as it is given.
 *
 * Someone else's transcription, tag or speaker is "not found", exactly as in the app. The services answer in the web
 * app's terms (`ResponseStatusException`: 400, 404, 409); the protocol layer turns those into the agent's answer.
 */
@Component
class McpWriteFacade(
    private val submissions: SubmissionService,
    private val tags: TagService,
    private val speakers: SpeakerService,
    private val transcriptSpeakers: TranscriptSpeakerService,
    private val suggestions: SuggestionService,
    private val phraseText: TranscriptTextService,
    private val summaryText: SummaryTextService,
) {
    // -- tags and speakers of the library ----------------------------------------------------------------

    fun createTag(userId: UUID, label: String?, description: String?): McpTagBrief {
        val ref = tags.create(userId, label, description)
        return tagBrief(userId, ref.id)
    }

    /** Renames a tag and/or changes its description; what is not given stays, a description of "" clears it. */
    fun updateTag(userId: UUID, id: String, label: String?, description: String?, descriptionGiven: Boolean): McpTagBrief {
        if (label == null && !descriptionGiven) throw ToolFailure("Give a new 'label' or a 'description'")
        val ref = tags.update(userId, uuid(id, "Tag not found"), label, description, descriptionGiven)
        return tagBrief(userId, ref.id)
    }

    fun deleteTag(userId: UUID, id: String): McpDeleted {
        val tagId = uuid(id, "Tag not found")
        val label = tagBrief(userId, tagId).label
        return McpDeleted("tag '$label'", tags.delete(userId, tagId))
    }

    fun createSpeaker(userId: UUID, name: String?, description: String?): McpSpeakerBrief {
        val ref = speakers.create(userId, name, description)
        return speakerBrief(userId, ref.id)
    }

    fun updateSpeaker(userId: UUID, id: String, name: String?, description: String?, descriptionGiven: Boolean): McpSpeakerBrief {
        if (name == null && !descriptionGiven) throw ToolFailure("Give a new 'name' or a 'description'")
        val ref = speakers.update(userId, uuid(id, "Speaker not found"), name, description, descriptionGiven)
        return speakerBrief(userId, ref.id)
    }

    fun deleteSpeaker(userId: UUID, id: String): McpDeleted {
        val speakerId = uuid(id, "Speaker not found")
        val name = speakerBrief(userId, speakerId).name
        return McpDeleted("speaker '$name'", speakers.delete(userId, speakerId))
    }

    // -- tags on a transcription -------------------------------------------------------------------------

    /** Puts a tag that is already in the library on the transcription (creating tags is create_tag, so a typo makes none). */
    fun addTag(userId: UUID, id: String, label: String?): McpTagOnTranscription {
        val submissionId = ownSubmission(userId, id)
        val tag = libraryTag(userId, label)
        tags.attach(userId, submissionId, tag.id, null, null)
        return McpTagOnTranscription(submissionId, tag, onTranscription = true)
    }

    fun removeTag(userId: UUID, id: String, label: String?): McpTagOnTranscription {
        val submissionId = ownSubmission(userId, id)
        val tag = libraryTag(userId, label)
        tags.detach(userId, submissionId, tag.id)
        return McpTagOnTranscription(submissionId, tag, onTranscription = false)
    }

    // -- title, summary, text ----------------------------------------------------------------------------

    fun rename(userId: UUID, id: String, title: String?): McpRenamed {
        val submissionId = ownSubmission(userId, id)
        val clean = title?.trim().orEmpty().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) throw ToolFailure("'title' can't be empty")
        if (clean.length > MAX_TITLE) throw ToolFailure("'title' is at most $MAX_TITLE characters")
        if (clean.any { it.isISOControl() }) throw ToolFailure("'title' has a character that is not allowed")
        submissions.updateTitle(submissionId, userId, clean)
        return McpRenamed(submissionId, clean)
    }

    /** Writes the summary (Markdown) by hand; answers with the text it replaced. Costs nothing: nothing is summarized. */
    fun setSummary(userId: UUID, id: String, text: String?): McpSummaryChange {
        val submissionId = ownSubmission(userId, id)
        val written = summaryText.set(userId, submissionId, text)
        return McpSummaryChange(submissionId, written.before, written.after)
    }

    /** Corrects the text of phrases (numbers as in get_transcript), all or none, and answers with each text it replaced. */
    fun editPhrases(userId: UUID, id: String, edits: List<Pair<Int, String?>>): List<McpPhraseEdit> {
        if (edits.size > MAX_EDITS) throw ToolFailure("At most $MAX_EDITS phrases in one call")
        val submissionId = ownSubmission(userId, id)
        return phraseText.editPhrases(userId, submissionId, edits).map { McpPhraseEdit(it.index, it.before, it.after) }
    }

    // -- who speaks --------------------------------------------------------------------------------------

    /**
     * Says that a diarizer voice (`SPEAKER_00`, see `voices` in get_transcription) is this library speaker in this
     * transcription. It is the Speaker Picker's "all by this speaker": every phrase of the voice becomes theirs and
     * marks that single phrases of the voice had are dropped.
     */
    fun setVoiceSpeaker(userId: UUID, id: String, voice: String?, speaker: String?, speakerId: String?): McpAssigned {
        val submissionId = ownSubmission(userId, id)
        val detail = submissions.getDetail(userId, submissionId) ?: throw ToolFailure(NOT_FOUND)
        val wanted = voice?.trim().orEmpty().ifEmpty { throw ToolFailure("'voice' is required") }
        val indexes = detail.segments.withIndex().filter { it.value.speaker == wanted }.map { it.index }.toSet()
        if (indexes.isEmpty()) {
            val known = detail.segments.mapNotNull { it.speaker }.distinct().sorted()
            throw ToolFailure("This transcription has no voice '$wanted'" + if (known.isEmpty()) " (it has no voices)" else "; its voices are $known")
        }
        val chosen = librarySpeaker(userId, speaker, speakerId)
        transcriptSpeakers.assign(userId, submissionId, TranscriptSpeakerService.Scope.ALL_BY_SPEAKER, indexes, chosen.id, null)
        return McpAssigned(submissionId, chosen.name, indexes.size)
    }

    /** Makes single phrases (numbers as in get_transcript) this speaker's, whatever their voice. */
    fun setPhraseSpeaker(userId: UUID, id: String, indexes: Set<Int>, speaker: String?, speakerId: String?): McpAssigned {
        if (indexes.isEmpty()) throw ToolFailure("Give at least one phrase in 'phrases'")
        if (indexes.size > MAX_PHRASES) throw ToolFailure("At most $MAX_PHRASES phrases in one call")
        val submissionId = ownSubmission(userId, id)
        val chosen = librarySpeaker(userId, speaker, speakerId)
        transcriptSpeakers.assign(userId, submissionId, TranscriptSpeakerService.Scope.SEGMENT, indexes, chosen.id, null)
        return McpAssigned(submissionId, chosen.name, indexes.size)
    }

    // -- the AI's proposals ------------------------------------------------------------------------------

    /**
     * Accepts one proposal: the title (renames the transcription), a tag (puts it on) or a speaker for a voice (settles
     * the voice, creating the speaker in the library if they are new). [value] is the tag label or the voice.
     */
    fun settleSuggestion(userId: UUID, id: String, kind: String?, value: String?, accept: Boolean): McpSettled {
        val submissionId = ownSubmission(userId, id)
        val which = SuggestionKind.parse(kind) ?: throw ToolFailure("'kind' is one of ${SuggestionKind.entries.map { it.param }}")
        val key = value?.trim().orEmpty()
        if (which != SuggestionKind.TITLE && key.isEmpty()) {
            throw ToolFailure("'value' is required for a ${which.param}: " + if (which == SuggestionKind.TAG) "the tag label" else "the voice, like SPEAKER_00")
        }
        val left = when (which) {
            SuggestionKind.TITLE -> if (accept) suggestions.acceptTitle(userId, submissionId) else suggestions.rejectTitle(userId, submissionId)
            SuggestionKind.TAG -> if (accept) suggestions.acceptTag(userId, submissionId, key) else suggestions.rejectTag(userId, submissionId, key)
            SuggestionKind.SPEAKER -> if (accept) suggestions.acceptSpeaker(userId, submissionId, key) else suggestions.rejectSpeaker(userId, submissionId, key)
        }
        return McpSettled(submissionId, left.toMcp())
    }

    /** Accepts everything still proposed (title, every tag, every speaker). */
    fun acceptAllSuggestions(userId: UUID, id: String): McpSettled {
        val submissionId = ownSubmission(userId, id)
        return McpSettled(submissionId, suggestions.acceptAll(userId, submissionId).toMcp())
    }

    /** Turns down everything still proposed; nothing on the transcription changes. */
    fun dismissAllSuggestions(userId: UUID, id: String): McpSettled {
        val submissionId = ownSubmission(userId, id)
        return McpSettled(submissionId, suggestions.dismissAll(userId, submissionId).toMcp())
    }

    // -- helpers -----------------------------------------------------------------------------------------

    /** The transcription's id if it is the user's; "not found" for a missing one and someone else's alike. */
    private fun ownSubmission(userId: UUID, id: String): UUID {
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull() ?: throw ToolFailure(NOT_FOUND)
        if (!submissions.owns(userId, submissionId)) throw ToolFailure(NOT_FOUND)
        return submissionId
    }

    private fun uuid(raw: String, notFound: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ToolFailure(notFound)

    private fun tagBrief(userId: UUID, id: UUID): McpTagBrief =
        tags.briefLibrary(userId).tags.firstOrNull { it.id == id }?.let { McpTagBrief(it.id, it.label, it.color, it.description) }
            ?: throw ToolFailure("Tag not found")

    private fun speakerBrief(userId: UUID, id: UUID): McpSpeakerBrief =
        speakers.briefLibrary(userId).speakers.firstOrNull { it.id == id }?.let { McpSpeakerBrief(it.id, it.name, it.color, it.description) }
            ?: throw ToolFailure("Speaker not found")

    private fun libraryTag(userId: UUID, raw: String?): McpTagBrief {
        val label = TagService.normalizeLabel(raw)
        return tags.briefLibrary(userId).tags.firstOrNull { it.label == label }?.let { McpTagBrief(it.id, it.label, it.color, it.description) }
            ?: throw ToolFailure("There is no tag '$label' in the library; create it first with create_tag")
    }

    /** A speaker of the library, by name (any case) or id. Making one up is create_speaker, so a typo makes none. */
    private fun librarySpeaker(userId: UUID, name: String?, id: String?): McpSpeakerBrief {
        val library = speakers.briefLibrary(userId).speakers
        val found = when {
            id != null -> library.firstOrNull { it.id.toString() == id.trim().lowercase() }
            name != null -> {
                val wanted = SpeakerService.normalizeName(name)
                library.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            }
            else -> throw ToolFailure("Give either 'speaker' (the name) or 'speaker_id'")
        }
        return found?.let { McpSpeakerBrief(it.id, it.name, it.color, it.description) }
            ?: throw ToolFailure("There is no such speaker in the library; create one first with create_speaker")
    }

    companion object {
        const val MAX_TITLE = 200
        const val MAX_EDITS = 100
        const val MAX_PHRASES = 500
        private const val NOT_FOUND = "Transcription not found"
    }
}

/** The proposals still open, as the tools show them. */
fun Suggestions?.toMcp(): McpSuggestions? = this?.let { s ->
    McpSuggestions(s.title, s.tags.map { it.label }, s.speakers.map { McpSpeakerProposal(it.voice, it.name, it.evidence) })
}
