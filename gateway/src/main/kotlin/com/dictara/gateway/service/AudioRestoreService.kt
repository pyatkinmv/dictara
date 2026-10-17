package com.dictara.gateway.service

import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.repository.SubmissionRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * What happens when the same bytes arrive again after the stored copy is gone (the bucket used to delete audio
 * after 90 days, so most of the old history has a transcript but no file). Every record with this content hash
 * whose object no longer exists is pointed at a copy that does: an existing live one, else the file just uploaded.
 */
@Service
class AudioRestoreService(
    private val audioMetaRepo: AudioMetaRepository,
    private val submissionRepo: SubmissionRepository,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * A file sent again under its real name replaces a made-up one. Older uploads from the Telegram bot were stored
     * under the name of a temporary file (`dictara-7802649983095675646.m4a`) and a voice message has only the bot's
     * own `audio_1792062859802.oga`; the real name (`20260608_213900.m4a`) usually says when it was recorded. Only the
     * uploader's own records of this content are renamed, and only those that carry such a made-up name: a name the
     * user kept is not touched, and neither is anything when the new name is made up as well.
     */
    fun adoptOriginalName(userId: UUID, contentHash: String, originalName: String) {
        if (isMadeUpName(originalName)) return
        val stale = audioMetaRepo.findByContentHashOrderByCreatedAtAsc(contentHash)
            .filter { it.userId == userId && isMadeUpName(it.originalName) }
            .map { it.id }
        if (stale.isEmpty()) return
        audioMetaRepo.updateOriginalName(stale, originalName)
        log.info("Audio name restored: {} record(s) with hash {} are now called {}", stale.size, contentHash, originalName)
    }

    /**
     * Heals every record that has [contentHash] and a missing file ([freshUri] is the object this upload just
     * created). Returns the user's own latest submission on a healed record, if any: the upload was a re-send of
     * a file whose audio was lost, so it only restores the audio and answers with the transcription that is
     * already there instead of transcribing again under possibly different options. Null when nothing was missing.
     */
    fun restore(userId: UUID, contentHash: String, freshUri: String): UUID? {
        val rows = audioMetaRepo.findByContentHashOrderByCreatedAtAsc(contentHash)
        if (rows.isEmpty()) return null

        val gone = HashMap<String, Boolean>()
        fun isGone(uri: String?) = uri == null || gone.getOrPut(uri) { audioStorage.isGone(AudioRef(uri)) }

        val dead = rows.filter { isGone(it.storageUri) }
        if (dead.isEmpty()) return null

        val target = rows.firstOrNull { !isGone(it.storageUri) }?.storageUri ?: freshUri
        audioMetaRepo.updateStorageUri(dead.map { it.id }, target)
        log.info("Audio restored: {} record(s) with hash {} now point at {}", dead.size, contentHash, target)

        val ownDead = dead.filter { it.userId == userId }.map { it.id }
        return if (ownDead.isEmpty()) null else submissionRepo.findLatestNonFailedByAudioIds(ownDead)?.id
    }

    companion object {
        /** `dictara-<digits>.<ext>` (a temp file of the old bot) and `audio_<digits>.<ext>` (the bot's name for a
         *  voice message): they carry no information about the recording. */
        private val MADE_UP_NAME = Regex("""^(dictara-|audio_)\d+(\.[A-Za-z0-9]+)?$""")

        fun isMadeUpName(name: String) = MADE_UP_NAME.matches(name)
    }
}
