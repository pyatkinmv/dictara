package com.dictara.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BotApiCleanupTest {

    private val now = 1_000_000_000_000L
    private val tenMin = 10 * 60 * 1000L

    private fun file(root: File, path: String, ageMs: Long): File {
        val f = File(root, path)
        f.parentFile.mkdirs()
        f.writeText("x")
        f.setLastModified(now - ageMs)
        return f
    }

    @Test
    fun `old finished downloads are picked, fresh ones are not`(@TempDir root: File) {
        val old = file(root, "123_TOKEN/music/file_1.m4a", tenMin + 1)
        val oldVoice = file(root, "123_TOKEN/voice/file_2.oga", 3 * tenMin)
        file(root, "123_TOKEN/music/file_3.m4a", tenMin - 1_000)

        assertEquals(setOf(old, oldVoice), BotApiCleanup.stale(root, now, tenMin).toSet())
    }

    @Test
    fun `bot-api's own state is never picked, however old`(@TempDir root: File) {
        file(root, "123_TOKEN/td.binlog", 30 * 24 * 3600_000L)
        file(root, "123_TOKEN/db.sqlite", 30 * 24 * 3600_000L)
        file(root, "tqueue.binlog", 30 * 24 * 3600_000L)
        file(root, "123_TOKEN/temp/136", 3 * tenMin) // a partial download, the daemon finishes or drops it itself
        val keep = file(root, "123_TOKEN/documents/file_9.m4a", 2 * tenMin)

        assertEquals(listOf(keep), BotApiCleanup.stale(root, now, tenMin))
    }

    @Test
    fun `files in deeper folders of a media folder count as downloads`(@TempDir root: File) {
        val deep = file(root, "123_TOKEN/videos/sub/file_4.mp4", 2 * tenMin)
        assertEquals(listOf(deep), BotApiCleanup.stale(root, now, tenMin))
    }

    @Test
    fun `a missing or empty directory gives nothing`(@TempDir root: File) {
        assertTrue(BotApiCleanup.stale(File(root, "nope"), now, tenMin).isEmpty())
        assertTrue(BotApiCleanup.stale(root, now, tenMin).isEmpty())
    }
}
