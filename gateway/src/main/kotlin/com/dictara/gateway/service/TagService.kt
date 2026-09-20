package com.dictara.gateway.service

import com.dictara.gateway.entity.TagEntity
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.repository.TagLibraryQueries
import com.dictara.gateway.repository.TagRepository
import com.dictara.gateway.repository.TranscriptRepository
import com.dictara.gateway.repository.TranscriptTagRepository
import com.dictara.gateway.util.TagColors
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.Locale
import java.util.UUID

/** A tag as the API shows it wherever one appears: with the color worked out from its name. */
data class TagRef(val id: UUID, val label: String) {
    val color: String get() = TagColors.of(label)
}

/**
 * The tag library of a user and the tags on their transcriptions (`/tags` and `/transcriptions/{id}/tags`).
 * Someone else's tag or transcription is reported as not found, like everywhere in the new API.
 */
@Service
class TagService(
    private val tagRepository: TagRepository,
    private val transcriptTagRepo: TranscriptTagRepository,
    private val transcriptRepo: TranscriptRepository,
    private val submissionRepo: SubmissionRepository,
    private val libraryQueries: TagLibraryQueries,
) {
    companion object {
        const val MAX_LABEL_LENGTH = 32
        const val MAX_DESCRIPTION_LENGTH = 500

        /** How many titles a library entry shows of the transcriptions that carry the tag. */
        const val SAMPLE_TITLES = 2

        private val labelPattern = Regex("^[\\p{L}\\p{N}_-]{1,$MAX_LABEL_LENGTH}$")

        /** The one form a label is stored in: trimmed, lower case. Anything else is a 400. */
        fun normalizeLabel(raw: String?): String {
            val label = raw?.trim()?.lowercase(Locale.ROOT)
            if (label.isNullOrEmpty() || !labelPattern.matches(label)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A tag name is 1–$MAX_LABEL_LENGTH letters, digits, hyphens or underscores",
                )
            }
            return label
        }

        private fun normalizeDescription(raw: String?): String? {
            val description = raw?.trim()?.takeIf { it.isNotEmpty() }
            if (description != null && description.length > MAX_DESCRIPTION_LENGTH) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A description is at most $MAX_DESCRIPTION_LENGTH characters")
            }
            return description
        }
    }

    data class LibraryEntry(
        val id: UUID,
        val label: String,
        val description: String?,
        val transcriptionCount: Int,
        /** The titles of the latest transcriptions that carry the tag, newest first. */
        val sampleTitles: List<String>,
    ) {
        val color: String get() = TagColors.of(label)
    }

    data class Library(val tags: List<LibraryEntry>, val taggedTranscriptions: Int)

    @Transactional(readOnly = true)
    fun library(userId: UUID): Library = Library(
        tags = libraryQueries.findLibrary(userId, SAMPLE_TITLES).map {
            LibraryEntry(it.id, it.name, it.description, it.transcriptionCount, it.sampleTitles)
        },
        taggedTranscriptions = libraryQueries.countTaggedTranscriptions(userId),
    )

    @Transactional
    fun create(userId: UUID, rawLabel: String?, rawDescription: String?): TagRef {
        val label = normalizeLabel(rawLabel)
        val description = normalizeDescription(rawDescription)
        if (tagRepository.findByUserIdAndName(userId, label) != null) throw taken()
        val saved = tagRepository.save(TagEntity(userId = userId, name = label, description = description))
        return TagRef(saved.id!!, saved.name)
    }

    /** Changes the name and/or description; what is not given stays. A description of "" clears it. */
    @Transactional
    fun update(userId: UUID, tagId: UUID, rawLabel: String?, rawDescription: String?, descriptionGiven: Boolean): TagRef {
        val tag = ownTag(userId, tagId)
        val label = if (rawLabel == null) tag.name else normalizeLabel(rawLabel)
        val description = if (descriptionGiven) normalizeDescription(rawDescription) else tag.description
        if (label != tag.name && tagRepository.findByUserIdAndName(userId, label) != null) throw taken()
        tagRepository.update(tagId, label, description)
        return TagRef(tagId, label)
    }

    /** Deletes the tag and takes it off every transcription; returns how many transcriptions that was. */
    @Transactional
    fun delete(userId: UUID, tagId: UUID): Int {
        val tag = ownTag(userId, tagId)
        val affected = tagRepository.countTranscriptions(tagId)
        tagRepository.delete(tag) // transcript_tags rows go with it (ON DELETE CASCADE)
        return affected
    }

    /**
     * Puts a tag on a transcription: the existing tag with [tagId], or the one called [rawLabel], which is
     * created in the library first when there is none. Putting a tag on twice changes nothing.
     */
    @Transactional
    fun attach(userId: UUID, submissionId: UUID, tagId: UUID?, rawLabel: String?, rawDescription: String?): TagRef {
        val transcriptId = ownTranscriptId(userId, submissionId)
        val tag = when {
            tagId != null -> ownTag(userId, tagId)
            else -> {
                val label = normalizeLabel(rawLabel)
                tagRepository.findByUserIdAndName(userId, label)
                    ?: tagRepository.save(TagEntity(userId = userId, name = label, description = normalizeDescription(rawDescription)))
            }
        }
        if (!transcriptTagRepo.existsByTranscriptIdAndTagId(transcriptId, tag.id!!)) {
            transcriptTagRepo.insert(transcriptId, tag.id)
        }
        return TagRef(tag.id, tag.name)
    }

    /** Takes a tag off one transcription; it stays in the library. Not being on it is not an error. */
    @Transactional
    fun detach(userId: UUID, submissionId: UUID, tagId: UUID) {
        val transcriptId = ownTranscriptId(userId, submissionId)
        val tag = ownTag(userId, tagId)
        transcriptTagRepo.deleteByTranscriptIdAndTagId(transcriptId, tag.id!!)
    }

    private fun ownTag(userId: UUID, tagId: UUID): TagEntity =
        tagRepository.findById(tagId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found")

    /** The transcript of the user's submission; 404 for someone else's or a missing one, 409 while there is no transcript. */
    private fun ownTranscriptId(userId: UUID, submissionId: UUID): UUID {
        val submission = submissionRepo.findById(submissionId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found")
        return transcriptRepo.findBySubmissionId(submission.id!!)?.id
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "The transcript is not ready yet")
    }

    private fun taken() = ResponseStatusException(HttpStatus.CONFLICT, "A tag with this name already exists")
}
