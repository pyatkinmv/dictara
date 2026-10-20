package com.dictara.gateway.mcp.facade

import com.dictara.gateway.plan.PlanService
import com.dictara.gateway.service.ListSort
import com.dictara.gateway.service.ListStatuses
import com.dictara.gateway.service.SpeakerService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.TagService
import com.dictara.gateway.service.TranscriptionListFilter
import org.springframework.stereotype.Component
import java.util.UUID

/** An error to show the agent as the result of its call (not found, a bad argument), not a failure of the server. */
class ToolFailure(message: String) : RuntimeException(message)

data class McpUsage(val used: Int, val max: Int?, val unit: String)
data class McpTokenInfo(val name: String, val access: String)
data class McpWhoAmI(val plan: String, val usage: McpUsage, val token: McpTokenInfo)

data class McpTagBrief(val id: UUID, val label: String, val color: String, val description: String?)
data class McpSpeakerBrief(val id: UUID, val name: String, val color: String, val description: String?)

data class McpTranscriptionItem(
    val id: UUID,
    val title: String,
    val fileName: String,
    val status: String,
    val uploadedAt: String,
    val recordedAt: String?,
    val durationS: Double?,
    val speakerCount: Int?,
    val tags: List<String>,
)

data class McpSearchResult(val items: List<McpTranscriptionItem>, val page: Int, val size: Int, val hasNext: Boolean)

data class McpTranscriptionInfo(
    val id: UUID,
    val title: String,
    val fileName: String,
    val status: String,
    val uploadedAt: String,
    val recordedAt: String?,
    val durationS: Double?,
    val language: String?,
    val wordCount: Int,
    val phraseCount: Int,
    val tags: List<String>,
    val speakers: List<String>,
    /** Markdown, as the summarizer wrote it; null when there is none. */
    val summary: String?,
)

data class McpPhrase(val index: Int, val startS: Double, val endS: Double, val speaker: String?, val text: String)

data class McpTranscriptChunk(
    val id: UUID,
    val title: String,
    val totalPhrases: Int,
    /** Phrases that match the time window (all of them when none was given). */
    val matching: Int,
    val offset: Int,
    val returned: Int,
    /** Where to continue ([offset] of the next call), or absent when this was the last piece. */
    val nextOffset: Int?,
    val phrases: List<McpPhrase>,
)

/**
 * What the MCP tools may read, and the only thing they may call. It reads through the same services as the web app,
 * so what an agent sees and the rules about whose data it is are exactly what the app has. Every method takes the
 * user's id, which the protocol layer took from the token and never from the request.
 */
@Component
class McpReadFacade(
    private val submissions: SubmissionService,
    private val tags: TagService,
    private val speakers: SpeakerService,
    private val plans: PlanService,
) {
    fun whoAmI(userId: UUID, token: McpTokenInfo): McpWhoAmI {
        val info = plans.info(userId)
        return McpWhoAmI(info.planDisplayName, McpUsage(info.usage.used, info.usage.max, info.usage.unit), token)
    }

    fun listTags(userId: UUID): List<McpTagBrief> =
        tags.briefLibrary(userId).tags.map { McpTagBrief(it.id, it.label, it.color, it.description) }

    fun listSpeakers(userId: UUID): List<McpSpeakerBrief> =
        speakers.briefLibrary(userId).speakers.map { McpSpeakerBrief(it.id, it.name, it.color, it.description) }

    fun search(
        userId: UUID,
        query: String?,
        tagNames: Set<String>,
        speakerNames: Set<String>,
        statuses: Set<String>,
        sort: String?,
        page: Int,
        size: Int,
    ): McpSearchResult {
        statuses.firstOrNull { it !in ListStatuses.stored }?.let {
            throw ToolFailure("Unknown status '$it'; expected one of ${ListStatuses.stored.keys.sorted()}")
        }
        val listSort = if (sort == null) ListSort.NEWEST else ListSort.parse(sort)
            ?: throw ToolFailure("Unknown sort '$sort'; expected one of ${ListSort.entries.map { it.param }}")
        if (page < 0) throw ToolFailure("page must be 0 or more")
        if (size !in 1..MAX_SEARCH_SIZE) throw ToolFailure("size must be between 1 and $MAX_SEARCH_SIZE")
        if ((query?.length ?: 0) > MAX_QUERY) throw ToolFailure("query is at most $MAX_QUERY characters")

        val filter = TranscriptionListFilter(
            search = query, statuses = statuses, tags = tagNames, speakers = speakerNames, sort = listSort,
        )
        val result = submissions.listPageForUser(userId, filter, page, size)
        val items = result.items.map {
            McpTranscriptionItem(
                it.jobId, it.title, it.fileName, it.status, it.createdAt, it.recordedAt,
                it.audioDurationS, it.speakerCount, it.tags.map { tag -> tag.label },
            )
        }
        return McpSearchResult(items, page, size, result.hasNext)
    }

    fun transcription(userId: UUID, id: String): McpTranscriptionInfo {
        val detail = detailOf(userId, id)
        val resolved = resolve(detail)
        return McpTranscriptionInfo(
            id = detail.jobId, title = detail.title, fileName = detail.fileName, status = detail.status,
            uploadedAt = detail.createdAt, recordedAt = detail.recordedAt, durationS = detail.audioDurationS,
            language = detail.language, wordCount = detail.wordCount, phraseCount = detail.segments.size,
            tags = detail.tags.map { it.label }, speakers = resolved.mapNotNull { it.speaker }.distinct(),
            summary = detail.summary,
        )
    }

    /**
     * The transcript in pieces: the phrases that start inside [fromS]..[toS] (all when not given), [limit] of them from
     * [offset]. A long recording has thousands of phrases, which an agent should not be handed at once.
     */
    fun transcript(userId: UUID, id: String, fromS: Double?, toS: Double?, offset: Int, limit: Int): McpTranscriptChunk {
        if (offset < 0) throw ToolFailure("offset must be 0 or more")
        if (limit !in 1..MAX_PHRASES) throw ToolFailure("limit must be between 1 and $MAX_PHRASES")
        val detail = detailOf(userId, id)
        val inWindow = resolve(detail).filter { (fromS == null || it.endS >= fromS) && (toS == null || it.startS <= toS) }
        val piece = inWindow.drop(offset).take(limit)
        val next = (offset + piece.size).takeIf { it < inWindow.size }
        return McpTranscriptChunk(detail.jobId, detail.title, detail.segments.size, inWindow.size, offset, piece.size, next, piece)
    }

    /** The transcription, or "not found" for one that does not exist and for someone else's alike (ids cannot be probed). */
    private fun detailOf(userId: UUID, id: String): SubmissionService.TranscriptionDetail {
        val submissionId = runCatching { UUID.fromString(id) }.getOrNull() ?: throw ToolFailure("Transcription not found")
        return submissions.getDetail(userId, submissionId) ?: throw ToolFailure("Transcription not found")
    }

    /** Every phrase with who said it: the speaker set on the phrase, else its voice's speaker, else "Speaker N". */
    private fun resolve(detail: SubmissionService.TranscriptionDetail): List<McpPhrase> {
        val byVoice = detail.voices.associate { it.voice to it.speaker.name }
        return detail.segments.mapIndexed { i, s ->
            val speaker = s.assignedSpeaker?.name ?: s.speaker?.let { voice -> byVoice[voice] ?: "Speaker ${voiceNumber(voice)}" }
            McpPhrase(i, s.start, s.end, speaker, s.text)
        }
    }

    /** `SPEAKER_00` is "Speaker 1", as the app shows it. */
    private fun voiceNumber(voice: String): String =
        voice.substringAfterLast('_').toIntOrNull()?.plus(1)?.toString() ?: voice

    companion object {
        const val MAX_SEARCH_SIZE = 50
        const val MAX_QUERY = 200
        const val MAX_PHRASES = 500
    }
}
