package com.dictara.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AudioNamesTest {

    // ── extension of a path ──────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
        "voice/file_1.oga, oga",
        "/var/lib/telegram-bot-api/123456:ABC-def/voice/file_12.OGA, oga",
        "music/file_0.mp3, mp3",
        "documents/file_5.m4a, m4a",
        "20260608_213900.m4a, m4a",
        "audio.tar.gz, gz",
    )
    fun `the extension of a path with one is lower case and without the dot`(path: String, expected: String) {
        assertEquals(expected, AudioNames.extensionOf(path))
    }

    @ParameterizedTest
    @CsvSource(
        "voice/file_1",
        "/var/lib/telegram-bot-api/123456:ABC-def/voice/file_2",
        "music/file_0",
        "file",
        "folder.with.dots/file_7",
    )
    fun `a path with no extension of its own has none, even when a folder in it has dots`(path: String) {
        assertNull(AudioNames.extensionOf(path))
    }

    @Test
    fun `nothing and empty have no extension`() {
        assertNull(AudioNames.extensionOf(null))
        assertNull(AudioNames.extensionOf(""))
        assertNull(AudioNames.extensionOf("voice/"))
    }

    @Test
    fun `a trailing dot or something that is not an extension is not one`() {
        assertNull(AudioNames.extensionOf("recording."))
        assertNull(AudioNames.extensionOf("recording.a very long tail"))
        assertNull(AudioNames.extensionOf("recording.tool-long"))
    }

    // ── from the MIME type ───────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource(
        "audio/ogg, oga",
        "AUDIO/OGG, oga",
        "'audio/ogg; codecs=opus', oga",
        "audio/mpeg, mp3",
        "audio/mp4, m4a",
        "audio/x-m4a, m4a",
        "audio/wav, wav",
        "audio/flac, flac",
        "video/mp4, mp4",
        "video/quicktime, mov",
        "video/webm, webm",
        "video/x-matroska, mkv",
    )
    fun `known MIME types give their extension`(mime: String, expected: String) {
        assertEquals(expected, AudioNames.extensionForMime(mime))
    }

    @Test
    fun `an unknown or missing MIME type gives none`() {
        assertNull(AudioNames.extensionForMime("application/pdf"))
        assertNull(AudioNames.extensionForMime("image/png"))
        assertNull(AudioNames.extensionForMime(""))
        assertNull(AudioNames.extensionForMime(null))
    }

    // ── the extension of the temporary copy ──────────────────────────────────────────────────────────────

    @Test
    fun `the path decides when it has an extension`() {
        assertEquals("oga", AudioNames.extension("voice/file_1.oga", "x.mp3", "audio/mpeg", "mp3"))
    }

    @Test
    fun `the case of the evening - a voice message whose path has no extension is Ogg, not bin`() {
        assertEquals("oga", AudioNames.extension("/var/lib/telegram-bot-api/1:A/voice/file_1", null, "audio/ogg", "oga"))
        assertEquals("oga", AudioNames.extension("/var/lib/telegram-bot-api/1:A/voice/file_3", null, null, "oga"))
    }

    @Test
    fun `without an extension in the path the name Telegram gave decides`() {
        assertEquals("m4a", AudioNames.extension("music/file_0", "call.m4a", "audio/mpeg", "mp3"))
    }

    @Test
    fun `then the MIME type`() {
        assertEquals("m4a", AudioNames.extension("music/file_0", null, "audio/mp4", "mp3"))
        assertEquals("mov", AudioNames.extension("videos/file_0", "", "video/quicktime", "mp4"))
    }

    @Test
    fun `then what that kind of message always is`() {
        assertEquals("mp4", AudioNames.extension("video_notes/file_9", null, null, "mp4"))
        assertEquals("mp3", AudioNames.extension("music/file_0", null, "application/x-weird", "mp3"))
    }

    @Test
    fun `no path at all still gives an extension`() {
        assertEquals("oga", AudioNames.extension(null, null, null, "oga"))
    }

    // ── the name the recording is sent under ────────────────────────────────────────────────────────────

    @Test
    fun `the name Telegram gave is kept as it is`() {
        assertEquals("20260608_213900.m4a", AudioNames.displayName("20260608_213900.m4a", "oga", 1L))
        assertEquals("Предательство сына (1).mp3", AudioNames.displayName("Предательство сына (1).mp3", "mp3", 1L))
    }

    @Test
    fun `a name with spaces around it is trimmed`() {
        assertEquals("call.m4a", AudioNames.displayName("  call.m4a ", "m4a", 1L))
    }

    @Test
    fun `a name with no extension gets one, as the gateway looks at it`() {
        assertEquals("interview.m4a", AudioNames.displayName("interview", "m4a", 1L))
    }

    @Test
    fun `no name gives audio and the time`() {
        assertEquals("audio_1792235614058.oga", AudioNames.displayName(null, "oga", 1792235614058))
        assertEquals("audio_5.mp3", AudioNames.displayName("", "mp3", 5))
        assertEquals("audio_5.mp3", AudioNames.displayName("   ", "mp3", 5))
    }

    @Test
    fun `the name of a voice message from the evening is accepted by the gateway - oga is a supported extension`() {
        val ext = AudioNames.extension("/var/lib/telegram-bot-api/1:A/voice/file_1", null, "audio/ogg", "oga")
        val supported = setOf("mp3", "mp4", "m4a", "wav", "ogg", "oga", "opus", "flac", "webm", "mkv", "avi", "mov")

        assertEquals(true, AudioNames.displayName(null, ext, 1L).substringAfterLast('.') in supported)
    }
}
