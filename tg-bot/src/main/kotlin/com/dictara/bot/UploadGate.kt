package com.dictara.bot

import java.util.concurrent.Semaphore

/**
 * Lets only [permits] files at a time through the heavy part of handling a recording (copying it from the Telegram
 * server's disk and sending it to the gateway). The others wait, and are let in in the order they came.
 *
 * A user can forward a whole chat of recordings in one go; with no limit every one of them was copied and sent at
 * the same moment, and the gateway ran out of memory. The wait is invisible to the user: the "Transcribing…" message
 * is already on the screen for every file.
 *
 * The place is given back when the work ends, however it ends (an error, or the thread being interrupted).
 */
class UploadGate(permits: Int) {
    init {
        require(permits >= 1) { "UploadGate needs at least one place, got $permits" }
    }

    private val places = Semaphore(permits, true)

    /** How many files are waiting for a place right now. */
    val waiting: Int get() = places.queueLength

    /** How many places are free right now. */
    val free: Int get() = places.availablePermits()

    /** Waits for a place, runs [work] and gives the place back. */
    fun <T> run(work: () -> T): T {
        places.acquire()
        try {
            return work()
        } finally {
            places.release()
        }
    }
}
