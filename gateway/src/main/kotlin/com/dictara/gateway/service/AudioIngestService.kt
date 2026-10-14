package com.dictara.gateway.service

import com.dictara.gateway.repository.AudioMetaRepository
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import com.dictara.gateway.storage.UploadResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Stores an uploaded file once per content. The audio is an object of its own, whoever sends it and with
 * whatever options: the hash is taken first, and when an object with that content still exists, the new record just
 * points at it and nothing is uploaded again. Only new (or lost) content goes to storage.
 */
@Service
class AudioIngestService(
    private val audioMetaRepo: AudioMetaRepository,
    private val audioStorage: AudioStorage,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun store(audioId: UUID, fileName: String, file: MultipartFile, contentType: String): UploadResult {
        val hash = sha256(file)
        val live = audioMetaRepo.findByContentHashOrderByCreatedAtAsc(hash)
            .mapNotNull { it.storageUri }
            .distinct()
            .firstOrNull { !audioStorage.isGone(AudioRef(it)) }
        if (live != null) {
            log.info("Audio {} already stored at {} (sha256={}), not uploading again", fileName, live, hash)
            return UploadResult(AudioRef(live), hash)
        }
        return audioStorage.upload(audioId, fileName, file.inputStream, file.size, contentType)
    }

    private fun sha256(file: MultipartFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
