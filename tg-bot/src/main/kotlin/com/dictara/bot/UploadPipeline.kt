package com.dictara.bot

import org.slf4j.LoggerFactory
import java.io.File

/** A recording as Telegram describes it: what to ask for, and what is known about its name and kind. */
data class TelegramFile(
    val fileId: String,
    /** The name Telegram gave the file, if it did (a document, an audio file; not a voice message). */
    val nameHint: String?,
    val mimeType: String? = null,
    /** The extension to use when nothing else says one, see [AudioNames]. */
    val defaultExt: String = "mp3",
)

/**
 * The heavy start of handling a recording: copy it from the Telegram server ([download]) and hand it to the gateway
 * ([send]), at most [UploadGate]'s number of files at a time. It ends when the gateway has taken the file, and the
 * temporary copy is deleted right then, whatever happened, rather than after the (possibly hours long) transcription.
 * Waiting for the transcript is not part of it and is not limited.
 */
class UploadPipeline(
    private val gate: UploadGate,
    private val download: (TelegramFile) -> Pair<File, String>,
) {
    private val log = LoggerFactory.getLogger(UploadPipeline::class.java)

    /** Waits for a place, downloads the file, runs [send] with the copy and its name, deletes the copy. */
    fun <T> submit(telegramFile: TelegramFile, send: (file: File, name: String) -> T): T = gate.run {
        val (file, name) = download(telegramFile)
        try {
            send(file, name)
        } finally {
            if (!file.delete() && file.exists()) log.warn("Could not delete the temporary copy {}", file)
        }
    }
}
