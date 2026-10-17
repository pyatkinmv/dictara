package com.dictara.bot

/**
 * What a recording from Telegram is called, and what its temporary copy ends with.
 *
 * The local telegram-bot-api names the files it keeps by their kind and a number (`voice/file_1`) and gives them an
 * extension only when it knows one; after it has been restarted (it crashed several times under a burst of files) it
 * often does not, and a copy called `….bin` is refused by the gateway as an unsupported format. So the extension is
 * taken from the path when it has a plausible one, else from the name Telegram gave the file, else from the file's
 * MIME type, else from what that kind of message always is (a voice message is Ogg Opus, a video note is MP4).
 */
object AudioNames {

    private val PLAUSIBLE_EXTENSION = Regex("^[a-z0-9]{1,5}$")

    private val MIME_EXTENSIONS = mapOf(
        "audio/ogg" to "oga",
        "application/ogg" to "oga",
        "audio/opus" to "opus",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/m4a" to "m4a",
        "audio/aac" to "m4a",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/webm" to "webm",
        "video/mp4" to "mp4",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/x-msvideo" to "avi",
    )

    /** The extension of the last part of [path] (lower case, no dot), or null when it has none or an odd one. */
    fun extensionOf(path: String?): String? {
        val last = path?.substringAfterLast('/')?.substringAfterLast("\\") ?: return null
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase().takeIf { PLAUSIBLE_EXTENSION.matches(it) }
    }

    /** The extension for a MIME type such as `audio/ogg; codecs=opus`, or null when it is not a known one. */
    fun extensionForMime(mime: String?): String? {
        val type = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return MIME_EXTENSIONS[type]
    }

    /** The extension for the temporary copy of a file whose path on the Telegram server is [telegramPath]. */
    fun extension(telegramPath: String?, nameHint: String?, mimeType: String?, defaultExt: String): String =
        extensionOf(telegramPath) ?: extensionOf(nameHint) ?: extensionForMime(mimeType) ?: defaultExt

    /**
     * The name the recording is sent under. The name Telegram gave it, when it has one (with [ext] added if the name
     * has no extension, as the gateway looks at it); else `audio_<time>.<ext>`.
     */
    fun displayName(nameHint: String?, ext: String, nowMs: Long): String {
        val hint = nameHint?.trim()?.takeIf { it.isNotEmpty() } ?: return "audio_$nowMs.$ext"
        return if (extensionOf(hint) != null) hint else "$hint.$ext"
    }
}
