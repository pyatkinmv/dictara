package com.dictara.gateway.service

import com.dictara.gateway.entity.SpeakerEntity
import com.dictara.gateway.repository.SpeakerLibraryQueries
import com.dictara.gateway.repository.SpeakerRepository
import com.dictara.gateway.util.NameColors
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** A speaker as the API shows it: with the color worked out from the name. */
data class SpeakerRef(val id: UUID, val name: String) {
    val color: String get() = NameColors.of(name)
}

/**
 * The speaker library of a user (`/speakers`). Someone else's speaker is reported as not found.
 * Which transcriptions a speaker is in is stored elsewhere (`transcript_voices`, `transcript_segments`); the
 * library only reads it, and deleting a speaker clears them from there through the foreign keys.
 */
@Service
class SpeakerService(
    private val speakerRepository: SpeakerRepository,
    private val libraryQueries: SpeakerLibraryQueries,
) {
    companion object {
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 500

        /** How many titles a library entry shows of the transcriptions the speaker is in. */
        const val SAMPLE_TITLES = 2

        /**
         * The name as it is stored: trimmed, inner runs of whitespace made one space, the case kept. Letters
         * of any script, digits, spaces and ordinary punctuation are fine; control characters are not.
         */
        fun normalizeName(raw: String?): String {
            val name = raw?.trim()?.replace(Regex("\\s+"), " ")
            if (name.isNullOrEmpty() || name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A speaker name is 1–$MAX_NAME_LENGTH characters, without control characters")
            }
            return name
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
        val name: String,
        val description: String?,
        val transcriptionCount: Int,
        /** The summed length of the speaker's phrases, in seconds. */
        val speakingS: Double,
        /** The titles of the latest transcriptions the speaker is in, newest first. */
        val sampleTitles: List<String>,
    ) {
        val color: String get() = NameColors.of(name)
    }

    data class Library(val speakers: List<LibraryEntry>)

    @Transactional(readOnly = true)
    fun library(userId: UUID): Library = Library(
        libraryQueries.findLibrary(userId, SAMPLE_TITLES).map {
            LibraryEntry(it.id, it.name, it.description, it.transcriptionCount, it.speakingS, it.sampleTitles)
        },
    )

    @Transactional
    fun create(userId: UUID, rawName: String?, rawDescription: String?): SpeakerRef {
        val name = normalizeName(rawName)
        val description = normalizeDescription(rawDescription)
        if (speakerRepository.findByUserIdAndNameIgnoringCase(userId, name) != null) throw taken()
        val saved = speakerRepository.save(SpeakerEntity(userId = userId, name = name, description = description))
        return SpeakerRef(saved.id!!, saved.name)
    }

    /** The user's speaker called [rawName] (whatever the case), created without a description when there is none. */
    @Transactional
    fun findOrCreate(userId: UUID, rawName: String?): SpeakerRef {
        val name = normalizeName(rawName)
        val existing = speakerRepository.findByUserIdAndNameIgnoringCase(userId, name)
        if (existing != null) return SpeakerRef(existing.id!!, existing.name)
        val saved = speakerRepository.save(SpeakerEntity(userId = userId, name = name))
        return SpeakerRef(saved.id!!, saved.name)
    }

    /** The speaker with this id if it is the user's. */
    @Transactional(readOnly = true)
    fun own(userId: UUID, speakerId: UUID): SpeakerRef {
        val speaker = ownSpeaker(userId, speakerId)
        return SpeakerRef(speaker.id!!, speaker.name)
    }


    /** Changes the name and/or description; what is not given stays. A description of "" clears it. */
    @Transactional
    fun update(userId: UUID, speakerId: UUID, rawName: String?, rawDescription: String?, descriptionGiven: Boolean): SpeakerRef {
        val speaker = ownSpeaker(userId, speakerId)
        val name = if (rawName == null) speaker.name else normalizeName(rawName)
        val description = if (descriptionGiven) normalizeDescription(rawDescription) else speaker.description
        val other = speakerRepository.findByUserIdAndNameIgnoringCase(userId, name)
        if (other != null && other.id != speakerId) throw taken()
        speakerRepository.update(speakerId, name, description)
        return SpeakerRef(speakerId, name)
    }

    /** Deletes the speaker; their phrases and voices go back to "nobody". Returns how many transcriptions they were in. */
    @Transactional
    fun delete(userId: UUID, speakerId: UUID): Int {
        val speaker = ownSpeaker(userId, speakerId)
        val affected = libraryQueries.countTranscriptions(speakerId)
        speakerRepository.delete(speaker)
        return affected
    }

    private fun ownSpeaker(userId: UUID, speakerId: UUID): SpeakerEntity =
        speakerRepository.findById(speakerId).orElse(null)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Speaker not found")

    private fun taken() = ResponseStatusException(HttpStatus.CONFLICT, "A speaker with this name already exists")
}
