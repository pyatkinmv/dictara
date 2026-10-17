package com.dictara.bot

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue

/** The bot's side of the conversation with the gateway, against a stand-in gateway that answers as the real one does. */
class DictaraClientTest {

    @TempDir lateinit var dir: File

    private lateinit var server: MockWebServer
    private lateinit var client: DictaraClient
    private lateinit var audio: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DictaraClient(server.url("/").toString().trimEnd('/'), pollIntervalMs = 5)
        audio = File(dir, "tmp.m4a").also { it.writeText("some audio") }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun status(status: String, extra: String = "") = json("""{"status":"$status"$extra}""")

    private fun done(text: String = "hello", summary: String? = "short summary") = json(
        """{"status":"done","duration_s":42.5,"result":{"formatted_text":"$text","audio_duration_s":600.0,
           "summary":${if (summary == null) "null" else "\"$summary\""}}}"""
    )

    private fun submit(name: String = "20260608_213900.m4a", onProgress: ((String) -> Unit)? = null) = client.submit(
        audio, name, model = "turbo", diarize = true, summaryMode = SummaryMode.AUTO, language = "auto", numSpeakers = null,
        telegramUserId = 77, telegramUsername = null, telegramFirstName = null, telegramLastName = null,
        chatId = 1011, telegramMessageId = 4883, onProgress = onProgress,
    )

    /** A dispatcher that serves the queued answers one after another to the requests whose path starts with [prefix]. */
    private fun serve(vararg answers: MockResponse) {
        val queue = ConcurrentLinkedQueue(answers.toList())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = queue.poll() ?: answers.last()
        }
    }

    // ── submit ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the file goes up under its original name, with the options and who sent it`() {
        server.enqueue(json("""{"job_id":"job-1","dedup":false}""", 202))

        val result = client.submit(
            audio, "20260608_213900.m4a", model = "turbo", diarize = true, summaryMode = SummaryMode.BRIEF,
            language = "ru", numSpeakers = 2,
            telegramUserId = 77, telegramUsername = "pyatkinmv", telegramFirstName = "Максим", telegramLastName = null,
            chatId = 1011, telegramMessageId = 4883,
        )

        assertEquals(SubmitResult("job-1", false), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/transcribe", request.requestUrl!!.encodedPath)
        assertEquals("turbo", request.requestUrl!!.queryParameter("model"))
        assertEquals("true", request.requestUrl!!.queryParameter("diarize"))
        assertEquals("brief", request.requestUrl!!.queryParameter("summary_mode"))
        assertEquals("ru", request.requestUrl!!.queryParameter("language"))
        assertEquals("2", request.requestUrl!!.queryParameter("num_speakers"))
        assertEquals("77", request.getHeader("X-Telegram-User-Id"))
        assertEquals("1011", request.getHeader("X-Telegram-Chat-Id"))
        assertEquals("4883", request.getHeader("X-Telegram-Message-Id"))
        assertEquals("pyatkinmv", request.getHeader("X-Telegram-Username"))
        assertEquals(URLEncoder.encode("Максим", "UTF-8"), request.getHeader("X-Telegram-First-Name"))
        assertNull(request.getHeader("X-Telegram-Last-Name"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("filename=\"20260608_213900.m4a\""), "the file name in the upload: $body")
        assertTrue(body.contains("some audio"))
    }

    @Test
    fun `language auto and no speaker count are left out of the request`() {
        server.enqueue(json("""{"job_id":"job-1"}""", 202))

        submit()

        val url = server.takeRequest().requestUrl!!
        assertNull(url.queryParameter("language"))
        assertNull(url.queryParameter("num_speakers"))
    }

    @Test
    fun `a file the gateway already knows is reported as a repeat`() {
        server.enqueue(json("""{"job_id":"job-9","dedup":true}""", 202))

        assertEquals(SubmitResult("job-9", true), submit())
    }

    @Test
    fun `an answer without the dedup flag is a new file`() {
        server.enqueue(json("""{"job_id":"job-2"}""", 202))

        assertFalse(submit().dedup)
    }

    @Test
    fun `the monthly limit comes out as a plan limit, with the gateway's words`() {
        server.enqueue(json("""{"message":"You have used all 10 transcriptions this month"}""", 429))

        val e = assertThrows(PlanLimitException::class.java) { submit() }

        assertEquals("You have used all 10 transcriptions this month", e.message)
        assertEquals(1, server.requestCount, "a refusal is not tried again")
    }

    @Test
    fun `a file that is too large comes out as such`() {
        server.enqueue(json("""{"message":"File is too large. Maximum allowed size is 400 MB."}""", 413))

        val e = assertThrows(FileTooLargeException::class.java) { submit() }

        assertEquals("File is too large. Maximum allowed size is 400 MB.", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `any other refusal is an error with the gateway's message and is not tried again`() {
        server.enqueue(json("""{"message":"Unsupported file type .exe"}""", 400))

        val e = assertThrows(RuntimeException::class.java) { submit() }

        assertEquals("Unsupported file type .exe", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refusal with no message still says what happened`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val e = assertThrows(RuntimeException::class.java) { submit() }

        assertTrue(e.message!!.contains("500") && e.message!!.contains("upstream exploded"), e.message)
    }

    @Test
    fun `a gateway that cannot be reached for a moment is tried again until it answers`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(json("""{"job_id":"job-3","dedup":false}""", 202))
        val messages = mutableListOf<String>()

        val result = submit(onProgress = { messages += it })

        assertEquals("job-3", result.jobId)
        assertEquals(3, server.requestCount)
        assertEquals(2, messages.count { it.contains("temporarily unavailable") }, "the user is told while it retries")
    }

    // ── poll ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a finished job gives the text, the summary and the lengths`() {
        serve(done("the whole transcript", "the summary"))

        val result = client.poll("job-1", diarize = true, summaryMode = SummaryMode.AUTO)

        assertEquals("the whole transcript", result.text)
        assertEquals("the summary", result.summary)
        assertEquals(42.5, result.durationSeconds)
        assertEquals(600.0, result.audioDurationSeconds)
        assertEquals("job-1", result.jobId)
    }

    @Test
    fun `a transcript with no summary has none`() {
        serve(done("text only", null))

        assertNull(client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF).summary)
    }

    @Test
    fun `a job that goes through every stage tells the user about each`() {
        serve(
            status("pending", ""","queue_position":3"""),
            status("processing", ""","elapsed_s":75.0,"progress":{"processed_s":300.0,"total_s":600.0,"phase":"transcribing"}"""),
            status("processing", ""","elapsed_s":300.0,"progress":{"processed_s":600.0,"total_s":600.0,"phase":"diarizing","diarize_progress":0.5}"""),
            status("summarizing"),
            done(),
        )
        val messages = mutableListOf<String>()

        val result = client.poll("job-1", diarize = true, summaryMode = SummaryMode.AUTO) { messages += it }

        assertEquals("hello", result.text)
        assertEquals(4, messages.size, messages.toString())
        assertTrue(messages[0].contains("Waiting in queue") && messages[0].contains("Position: 3"), messages[0])
        assertTrue(messages[1].contains("Transcribing audio") && messages[1].contains("50%") && messages[1].contains("5m 0s / 10m 0s"), messages[1])
        assertTrue(messages[1].contains("Speaker detection to follow") && messages[1].contains("Summarization to follow"), messages[1])
        assertTrue(messages[2].contains("Detecting speakers") && messages[2].contains("50%"), messages[2])
        assertTrue(messages[3].contains("Summarizing"), messages[3])
    }

    @Test
    fun `without speakers and summary the progress does not promise them`() {
        serve(
            status("processing", ""","progress":{"processed_s":60.0,"total_s":120.0,"phase":"transcribing"}"""),
            done(),
        )
        val messages = mutableListOf<String>()

        client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF) { messages += it }

        assertFalse(messages[0].contains("Speaker detection to follow"))
        assertFalse(messages[0].contains("Summarization to follow"))
    }

    @Test
    fun `the same stage is reported once, not on every look at the job`() {
        // the screen message is edited at most this often so that Telegram does not stop the bot
        serve(status("summarizing"), status("summarizing"), status("summarizing"), status("summarizing"), done())
        val messages = mutableListOf<String>()

        client.poll("job-1", diarize = false, summaryMode = SummaryMode.AUTO) { messages += it }

        assertEquals(1, messages.size, messages.toString())
    }

    @Test
    fun `a job the transcriber failed on ends with its error`() {
        serve(json("""{"status":"failed","error":"File has no audio track."}"""))

        val e = assertThrows(RuntimeException::class.java) { client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF) }

        assertEquals("File has no audio track.", e.message)
    }

    @Test
    fun `a failed job with no error text still ends`() {
        serve(json("""{"status":"failed"}"""))

        val e = assertThrows(RuntimeException::class.java) { client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF) }

        assertEquals("Unknown error", e.message)
    }

    @Test
    fun `a gateway that drops out for a moment while the job runs does not lose the job`() {
        server.dispatcher = object : Dispatcher() {
            private var calls = 0
            override fun dispatch(request: RecordedRequest): MockResponse = when (++calls) {
                1 -> status("processing")
                2, 3 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                else -> done("after the outage")
            }
        }
        val messages = mutableListOf<String>()

        val result = client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF) { messages += it }

        assertEquals("after the outage", result.text)
        assertTrue(messages.any { it.contains("temporarily unavailable") })
    }

    @Test
    fun `an answer that says nothing yet is looked at again`() {
        serve(MockResponse().setResponseCode(200).setBody(""), status("some-new-state"), done("finally"))

        assertEquals("finally", client.poll("job-1", diarize = false, summaryMode = SummaryMode.OFF).text)
    }

    @Test
    fun `the right job is asked about`() {
        serve(done())

        client.poll("job-42", diarize = false, summaryMode = SummaryMode.OFF)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/jobs/job-42", request.requestUrl!!.encodedPath)
    }

    // ── both together ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sending and waiting in one go gives the transcript and remembers a repeat`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "POST") json("""{"job_id":"job-5","dedup":true}""", 202) else done("the old transcript")
        }

        val result = client.transcribe(
            audio, "a.m4a", "turbo", true, SummaryMode.AUTO, "auto", null,
            telegramUserId = 1, chatId = 2,
        )

        assertEquals("the old transcript", result.text)
        assertEquals("job-5", result.jobId)
        assertTrue(result.dedup)
    }
}
