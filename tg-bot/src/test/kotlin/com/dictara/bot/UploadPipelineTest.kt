package com.dictara.bot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UploadPipelineTest {

    @TempDir lateinit var dir: File

    private lateinit var gate: UploadGate
    private val downloads = AtomicInteger()

    /** A download that makes a real file, as the bot does, named after the hint (or made up when there is none). */
    private fun fakeDownload(fileId: String, nameHint: String?): Pair<File, String> {
        downloads.incrementAndGet()
        val file = File.createTempFile("dictara-", ".m4a", dir)
        file.writeText("bytes of $fileId")
        return file to (nameHint ?: "audio_$fileId.oga")
    }

    private fun pipeline(download: (String, String?) -> Pair<File, String> = ::fakeDownload) = UploadPipeline(gate, download)

    private fun filesLeft() = dir.listFiles()!!.size

    @BeforeEach
    fun setUp() {
        gate = UploadGate(3)
    }

    @Test
    fun `the gateway step gets the file and the name it was sent with`() {
        val seen = pipeline().submit("id-1", "20260608_213900.m4a") { file, name ->
            "${file.readText()} / $name"
        }

        assertEquals("bytes of id-1 / 20260608_213900.m4a", seen)
    }

    @Test
    fun `a recording with no name of its own gets the name the download made up`() {
        val name = pipeline().submit("id-2", null) { _, n -> n }

        assertEquals("audio_id-2.oga", name)
    }

    @Test
    fun `the copy is there while the file is being sent and gone as soon as it is sent`() {
        var existedWhileSending = false

        pipeline().submit("id-3", "a.m4a") { file, _ -> existedWhileSending = file.exists() }

        assertTrue(existedWhileSending)
        assertEquals(0, filesLeft())
    }

    @Test
    fun `the copy is deleted and the error comes out when the gateway refuses the file`() {
        val e = assertThrows(PlanLimitException::class.java) {
            pipeline().submit<Unit>("id-4", "a.m4a") { _, _ -> throw PlanLimitException("Monthly limit reached") }
        }

        assertEquals("Monthly limit reached", e.message)
        assertEquals(0, filesLeft())
        assertEquals(3, gate.free)
    }

    @Test
    fun `a failed download sends nothing and gives the place back`() {
        val sent = AtomicInteger()
        val broken = pipeline { _, _ -> throw IOException("telegram-bot-api is down") }

        assertThrows(IOException::class.java) { broken.submit("id-5", "a.m4a") { _, _ -> sent.incrementAndGet() } }

        assertEquals(0, sent.get())
        assertEquals(3, gate.free)
        assertEquals(0, filesLeft())
    }

    @Test
    fun `the place is kept for the whole download and send, and given back after it`() {
        var freeWhileSending = -1

        pipeline().submit("id-6", "a.m4a") { _, _ -> freeWhileSending = gate.free }

        assertEquals(2, freeWhileSending)
        assertEquals(3, gate.free)
    }

    @Test
    fun `waiting for the transcript is not part of it, so a slow transcription does not hold a place`() {
        // what the bot does after submit(): poll for the result. Here it is a long wait done after the place is back.
        val jobId = pipeline().submit("id-7", "long.m4a") { _, _ -> "job-7" }

        assertEquals(3, gate.free)
        assertEquals("job-7", jobId)
    }

    @Test
    fun `many files at once - never more than the limit are copied and sent together, every one is, no copies stay`() {
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val sentNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(50)
        try {
            val results = (1..50).map { n ->
                pool.submit<String> {
                    pipeline().submit("id-$n", "file-$n.m4a") { file, name ->
                        peak.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                        Thread.sleep(10)
                        sentNames.add(name)
                        inside.decrementAndGet()
                        "job-for-${file.readText()}"
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals((1..50).map { "job-for-bytes of id-$it" }, results, "each file got the result of its own upload")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(50, sentNames.toSet().size)
        assertEquals(50, downloads.get())
        assertEquals(3, peak.get())
        assertEquals(0, filesLeft())
        assertEquals(3, gate.free)
        assertFalse(gate.waiting > 0)
    }

    @Test
    fun `some files failing in a crowd do not stop the others or leave copies behind`() {
        val pool = Executors.newFixedThreadPool(30)
        try {
            val outcomes = (1..30).map { n ->
                pool.submit<Boolean> {
                    try {
                        pipeline().submit("id-$n", "f$n.m4a") { _, _ -> if (n % 3 == 0) throw RuntimeException("500") else true }
                    } catch (_: RuntimeException) {
                        false
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(20, outcomes.count { it })
            assertEquals(10, outcomes.count { !it })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, filesLeft())
        assertEquals(3, gate.free)
    }
}
