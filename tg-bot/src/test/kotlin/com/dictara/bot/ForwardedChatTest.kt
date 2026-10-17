package com.dictara.bot

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The evening this was written for: a user forwards a whole chat of recordings to the bot at once. Everything from
 * the bot's side is real here — the gate, the pipeline, the client — and the gateway is a stand-in that counts how many
 * files are being sent to it at the same moment and answers like the real one.
 */
class ForwardedChatTest {

    @TempDir lateinit var dir: File

    private lateinit var gateway: MockWebServer
    private lateinit var client: DictaraClient
    private lateinit var gate: UploadGate
    private lateinit var pipeline: UploadPipeline

    private val receiving = AtomicInteger()
    private val peakReceiving = AtomicInteger()
    private val nameOfJob = ConcurrentHashMap<String, String>()
    private val jobsMade = AtomicInteger()
    private val receivedPerName = ConcurrentHashMap<String, AtomicInteger>()

    /** Names for which the stand-in gateway answers with a refusal instead of taking the file. */
    private val refused = ConcurrentHashMap.newKeySet<String>()

    @BeforeEach
    fun setUp() {
        gateway = MockWebServer()
        gateway.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (request.method == "POST" && path == "/transcribe") {
                    val name = Regex("filename=\"([^\"]+)\"").find(request.body.readUtf8())!!.groupValues[1]
                    peakReceiving.accumulateAndGet(receiving.incrementAndGet(), ::maxOf)
                    try {
                        Thread.sleep(25) // the upload takes a moment
                        receivedPerName.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                        if (name.endsWith(".bin")) {
                            return MockResponse().setResponseCode(400).setBody("""{"message":"Unsupported format: .bin"}""")
                        }
                        if (name in refused) {
                            return MockResponse().setResponseCode(429).setBody("""{"message":"limit reached"}""")
                        }
                        val job = "job-${jobsMade.incrementAndGet()}"
                        nameOfJob[job] = name
                        return MockResponse().setResponseCode(202).setBody("""{"job_id":"$job","dedup":false}""")
                    } finally {
                        receiving.decrementAndGet()
                    }
                }
                if (request.method == "GET" && path.startsWith("/jobs/")) {
                    val job = path.removePrefix("/jobs/")
                    return MockResponse().setBody(
                        """{"status":"done","duration_s":1.0,"result":{"formatted_text":"transcript of ${nameOfJob[job]}"}}"""
                    )
                }
                return MockResponse().setResponseCode(404)
            }
        }
        gateway.start()
        client = DictaraClient(gateway.url("/").toString().trimEnd('/'), pollIntervalMs = 5)
        gate = UploadGate(3)
        pipeline = UploadPipeline(gate) { telegramFile ->
            val file = File.createTempFile("dictara-", ".m4a", dir)
            file.writeText("audio of ${telegramFile.fileId}")
            file to (telegramFile.nameHint ?: "audio_${telegramFile.fileId}.oga")
        }
    }

    @AfterEach
    fun tearDown() {
        gateway.shutdown()
    }

    /** What the bot does for one forwarded recording, as far as the gateway is concerned. */
    private fun handle(n: Int): String {
        val submitted = pipeline.submit(TelegramFile("file-$n", "recording-$n.m4a")) { file, name ->
            client.submit(
                file, name, "turbo", true, SummaryMode.AUTO, "auto", null,
                telegramUserId = 77, chatId = 1011, telegramMessageId = n.toLong(),
            )
        }
        return client.poll(submitted.jobId, true, SummaryMode.AUTO).text
    }

    private fun forward(count: Int): List<Result<String>> {
        val pool = Executors.newCachedThreadPool() // the bot's own pool: one thread per recording, no limit
        try {
            return (1..count).map { n -> pool.submit<Result<String>> { runCatching { handle(n) } } }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `eighty recordings forwarded at once are never sent to the gateway more than three at a time, and all are answered`() {
        val results = forward(80)

        assertEquals((1..80).map { "transcript of recording-$it.m4a" }, results.map { it.getOrThrow() },
            "everyone got the transcript of their own recording")
        assertTrue(peakReceiving.get() <= 3, "files at the gateway at one moment: ${peakReceiving.get()}")
        assertTrue(peakReceiving.get() >= 2, "the crowd really was let through in parallel: ${peakReceiving.get()}")
        assertEquals(80, jobsMade.get())
        assertEquals(0, dir.listFiles()!!.size, "no temporary copies stay on the disk")
        assertEquals(3, gate.free)
        assertEquals(0, gate.waiting)
    }

    @Test
    fun `every recording is sent exactly once`() {
        forward(40)

        assertEquals(40, receivedPerName.size)
        assertTrue(receivedPerName.values.all { it.get() == 1 }, receivedPerName.toString())
    }

    @Test
    fun `recordings the gateway refuses are reported to their owner and do not hold up the rest`() {
        (1..60).filter { it % 4 == 0 }.forEach { refused += "recording-$it.m4a" }

        val results = forward(60)

        assertEquals(15, results.count { it.isFailure })
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is PlanLimitException) }
        assertEquals(45, results.count { it.isSuccess })
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(3, gate.free)
    }

    @Test
    fun `a single recording goes through at once`() {
        val started = System.currentTimeMillis()

        assertEquals("transcript of recording-1.m4a", forward(1)[0].getOrThrow())

        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun `a slow transcription does not take a place from the files behind it`() {
        // the transcript of the first file is slow to come, but its place is free as soon as it is sent
        gateway.dispatcher.let { original ->
            gateway.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.method == "GET" && path == "/jobs/job-1") {
                        return MockResponse().setBody("""{"status":"processing","progress":{"processed_s":1.0,"total_s":600.0,"phase":"transcribing"}}""")
                            .setBodyDelay(0, TimeUnit.MILLISECONDS)
                    }
                    return original.dispatch(request)
                }
            }
        }
        val pool = Executors.newCachedThreadPool()
        try {
            val first = pool.submit<Result<String>> { runCatching { handle(1) } } // never finishes: job-1 stays "processing"
            while (jobsMade.get() < 1) Thread.sleep(5)

            val others = (2..10).map { n -> pool.submit<Result<String>> { runCatching { handle(n) } } }
                .map { it.get(30, TimeUnit.SECONDS) }

            assertTrue(others.all { it.isSuccess }, others.toString())
            assertTrue(!first.isDone, "the slow one is still waiting for its transcript")
            assertEquals(3, gate.free)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `voice messages the Telegram server keeps without an extension are sent as Ogg, not refused as bin`() {
        // the evening after telegram-bot-api had crashed: its paths were voice/file_1, voice/file_2 ... and the
        // bot's copies ended in .bin, which the gateway refuses
        val voicePipeline = UploadPipeline(gate) { telegramFile ->
            val telegramPath = "/var/lib/telegram-bot-api/1:A/voice/file_${telegramFile.fileId}"
            val ext = AudioNames.extension(telegramPath, telegramFile.nameHint, telegramFile.mimeType, telegramFile.defaultExt)
            val file = File.createTempFile("dictara-", ".$ext", dir)
            file.writeText("voice ${telegramFile.fileId}")
            file to AudioNames.displayName(telegramFile.nameHint, ext, 1792235614000L + telegramFile.fileId.toLong())
        }
        val pool = Executors.newCachedThreadPool()
        try {
            val results = (1..30).map { n ->
                pool.submit<Result<String>> {
                    runCatching {
                        val submitted = voicePipeline.submit(TelegramFile("$n", null, "audio/ogg", "oga")) { file, name ->
                            client.submit(file, name, "turbo", true, SummaryMode.AUTO, "auto", null, telegramUserId = 77, chatId = 1011)
                        }
                        client.poll(submitted.jobId, true, SummaryMode.AUTO).text
                    }
                }
            }.map { it.get(60, TimeUnit.SECONDS) }

            assertTrue(results.all { it.isSuccess }, results.filter { it.isFailure }.toString())
            assertEquals((1..30).map { "transcript of audio_${1792235614000L + it}.oga" }, results.map { it.getOrThrow() })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, dir.listFiles()!!.size)
    }
}
