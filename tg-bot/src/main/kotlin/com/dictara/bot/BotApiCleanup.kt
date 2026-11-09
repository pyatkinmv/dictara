package com.dictara.bot

import java.io.File

/**
 * Picks which files of the local telegram-bot-api's data directory the bot may delete.
 *
 * The directory is not ours alone. Next to the downloaded recordings (`<root>/<token>/music/file_1.m4a`, `voice/`,
 * `documents/`, ...) bot-api keeps its own state: `td.binlog`, `tqueue.binlog` and the partial downloads in `temp/`.
 * Those files are not touched for a long time while the bot is idle, so an age rule over the whole tree deleted them:
 * bot-api then crashed (`Failed to unlink old binlog`, 7 times) and, started again, forgot its files; downloads of
 * large recordings stalled and the user got "Something went wrong".
 *
 * Only a finished download qualifies: a file inside a kind-of-media folder one level under the token folder, never
 * `temp/`, never a file lying directly in the root or in the token folder.
 */
object BotApiCleanup {

    /** Folders under `<root>/<token>/` that bot-api uses for its own work, not for finished downloads. */
    private val OWN_FOLDERS = setOf("temp")

    /** Files older than [maxAgeMs] at [now] that are finished downloads and safe to remove. */
    fun stale(root: File, now: Long, maxAgeMs: Long): List<File> =
        downloads(root).filter { it.lastModified() < now - maxAgeMs }

    /** Every finished download under [root]: `<root>/<bot folder>/<media folder>/<file>` (deeper files count too). */
    fun downloads(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val botFolders = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return botFolders.flatMap { bot ->
            val mediaFolders = bot.listFiles { f -> f.isDirectory && f.name !in OWN_FOLDERS } ?: emptyArray()
            mediaFolders.flatMap { media -> media.walkTopDown().filter { it.isFile }.toList() }
        }
    }
}
