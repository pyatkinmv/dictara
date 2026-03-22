package com.dictara.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TranscriptResult(
    val text: String,
    val durationSeconds: Double?,
    val audioDurationSeconds: Double? = null,
    val summary: String? = null,
    val jobId: String = "",
)

class DictaraClient(private val baseUrl: String) {
    private val log = LoggerFactory.getLogger(DictaraClient::class.java)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

    data class LoginNotification(val id: Long, val chatId: String, val token: String)
    data class PendingDelivery(val jobId: String, val chatId: Long, val telegramMessageId: Long?, val status: String, val error: String?)

    fun markBotStarted(telegramUserId: Long) {
        val body = mapper.writeValueAsString(mapOf("telegram_user_id" to telegramUserId.toString()))
        http.newCall(
            Request.Builder()
                .url("$baseUrl/auth/bot-started")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()
    }

    fun fetchPendingLoginNotifications(): List<LoginNotification> {
        val resp = http.newCall(Request.Builder().url("$baseUrl/auth/pending-login-notifications").get().build()).execute()
        val body = resp.body?.string() ?: "[]"
        @Suppress("UNCHECKED_CAST")
        val list = mapper.readValue(body, List::class.java) as List<Map<String, String>>
        return list.map { m -> LoginNotification(m["id"]!!.toLong(), m["chatId"]!!, m["token"]!!) }
    }

    fun ackLoginNotification(id: Long) {
        http.newCall(
            Request.Builder()
                .url("$baseUrl/auth/pending-login-notifications/$id/ack")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()
    }

    fun fetchPendingDeliveries(): List<PendingDelivery> {
        val resp = http.newCall(Request.Builder().url("$baseUrl/telegram/pending-deliveries").get().build()).execute()
        val body = resp.body?.string() ?: "[]"
        @Suppress("UNCHECKED_CAST")
        val list = mapper.readValue(body, List::class.java) as List<Map<String, Any?>>
        return list.map { m ->
            PendingDelivery(
                jobId = m["job_id"] as String,
                chatId = (m["chat_id"] as Number).toLong(),
                telegramMessageId = (m["telegram_message_id"] as Number?)?.toLong(),
                status = m["status"] as String,
                error = m["error"] as String?,
            )
        }
    }

    fun ackDelivery(jobId: String): Boolean {
        val resp = http.newCall(
            Request.Builder()
                .url("$baseUrl/telegram/deliveries/$jobId/ack")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        val body = resp.body?.string() ?: ""
        val claimed = runCatching { mapper.readTree(body)["claimed"]?.asBoolean() }.getOrNull() ?: false
        log.info("ackDelivery: jobId={} httpStatus={} body={} claimed={}", jobId, resp.code, body, claimed)
        return claimed
    }

    fun fetchJobResult(jobId: String): TranscriptResult {
        val response = http.newCall(Request.Builder().url("$baseUrl/jobs/$jobId").get().build()).execute()
        val root = mapper.readTree(response.body?.string() ?: "{}")
        val result = root["result"]
        return TranscriptResult(
            text = result?.get("formatted_text")?.asText() ?: "",
            durationSeconds = root["duration_s"]?.takeIf { !it.isNull }?.asDouble(),
            audioDurationSeconds = result?.get("audio_duration_s")?.takeIf { !it.isNull }?.asDouble(),
            summary = result?.get("summary")?.takeIf { !it.isNull }?.asText(),
            jobId = jobId,
        )
    }

    fun getPendingLoginForUsername(username: String): String? {
        val resp = http.newCall(
            Request.Builder()
                .url("$baseUrl/auth/pending-login-for-username?username=${URLEncoder.encode(username, "UTF-8")}")
                .get()
                .build()
        ).execute()
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        return runCatching { mapper.readTree(body)["token"]?.asText() }.getOrNull()
    }

    fun confirmLoginCallback(
        token: String,
        telegramUserId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
    ) {
        val body = mapper.writeValueAsString(
            mapOf(
                "token" to token,
                "telegram_user_id" to telegramUserId.toString(),
                "telegram_username" to telegramUsername,
                "telegram_first_name" to telegramFirstName,
                "telegram_last_name" to telegramLastName,
            ).filterValues { it != null }
        )
        http.newCall(
            Request.Builder()
                .url("$baseUrl/auth/login-link/confirm-callback")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()
    }

    fun rejectLogin(token: String) {
        val body = mapper.writeValueAsString(mapOf("token" to token))
        http.newCall(
            Request.Builder()
                .url("$baseUrl/auth/login-link/reject")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()
    }

    fun fetchSupportedExtensions(): Set<String> = try {
        val response = http.newCall(Request.Builder().url("$baseUrl/formats").get().build()).execute()
        val root = mapper.readTree(response.body?.string() ?: "{}")
        root["extensions"]?.map { it.asText() }?.toSet() ?: emptySet()
    } catch (_: Exception) {
        setOf("mp3", "mp4", "m4a", "wav", "ogg", "oga", "opus", "flac", "webm", "mkv", "avi", "mov")
    }

    fun transcribe(
        audioFile: File,
        model: String,
        diarize: Boolean,
        summaryMode: SummaryMode = SummaryMode.AUTO,
        language: String = "auto",
        numSpeakers: Int? = null,
        telegramUserId: Long,
        telegramUsername: String? = null,
        telegramFirstName: String? = null,
        telegramLastName: String? = null,
        chatId: Long,
        telegramMessageId: Long? = null,
        onProgress: ((String) -> Unit)? = null,
    ): TranscriptResult {
        val jobId = submitWithRetry(audioFile, model, diarize, summaryMode, language, numSpeakers,
            telegramUserId, telegramUsername, telegramFirstName, telegramLastName, chatId, telegramMessageId, onProgress)
        return pollJob(jobId, diarize, summaryMode, onProgress)
    }

    private fun submitWithRetry(
        audioFile: File,
        model: String,
        diarize: Boolean,
        summaryMode: SummaryMode,
        language: String,
        numSpeakers: Int?,
        telegramUserId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        chatId: Long,
        telegramMessageId: Long?,
        onProgress: ((String) -> Unit)?,
    ): String {
        var pollInterval = 5_000L
        val failureStart = System.currentTimeMillis()
        while (true) {
            try {
                return submitJob(audioFile, model, diarize, summaryMode, language, numSpeakers,
                    telegramUserId, telegramUsername, telegramFirstName, telegramLastName, chatId, telegramMessageId)
            } catch (e: RuntimeException) {
                throw e  // non-retryable (e.g. 4xx from gateway)
            } catch (e: Exception) {
                if (System.currentTimeMillis() - failureStart > 10 * 60 * 1000L)
                    throw RuntimeException("Server unavailable for 10 minutes")
                onProgress?.invoke("⚠️ Server temporarily unavailable, retrying...")
                Thread.sleep(pollInterval)
                pollInterval = minOf(pollInterval * 2, 300_000L)
            }
        }
    }

    private fun submitJob(
        file: File,
        model: String,
        diarize: Boolean,
        summaryMode: SummaryMode,
        language: String,
        numSpeakers: Int?,
        telegramUserId: Long,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
        chatId: Long,
        telegramMessageId: Long?,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val url = buildString {
            append("$baseUrl/transcribe?model=$model&diarize=$diarize")
            append("&summary_mode=${summaryMode.name.lowercase()}")
            if (language != "auto") append("&language=$language")
            if (numSpeakers != null) append("&num_speakers=$numSpeakers")
        }

        val response = http.newCall(
            Request.Builder().url(url).post(body)
                .header("X-Telegram-User-Id", telegramUserId.toString())
                .header("X-Telegram-Chat-Id", chatId.toString())
                .apply { if (telegramMessageId != null) header("X-Telegram-Message-Id", telegramMessageId.toString()) }
                .apply {
                    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
                    telegramUsername?.let { header("X-Telegram-Username", enc(it)) }
                    telegramFirstName?.let { header("X-Telegram-First-Name", enc(it)) }
                    telegramLastName?.let { header("X-Telegram-Last-Name", enc(it)) }
                }
                .build()
        ).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val message = runCatching { mapper.readTree(responseBody)["message"]?.asText() }.getOrNull()
            throw RuntimeException(message ?: "Submit failed (${response.code}): $responseBody")
        }
        return mapper.readTree(responseBody)["job_id"].asText()
    }

    private fun pollJob(
        jobId: String,
        diarize: Boolean,
        summaryMode: SummaryMode,
        onProgress: ((String) -> Unit)? = null,
    ): TranscriptResult {
        val deadline = System.currentTimeMillis() + 8 * 60 * 60 * 1000L
        var pollInterval = 5_000L
        var failureStartMs: Long? = null

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollInterval)
            try {
                val response = http.newCall(Request.Builder().url("$baseUrl/jobs/$jobId").get().build()).execute()
                val root = mapper.readTree(response.body?.string() ?: "{}")
                // Successful response — reset backoff
                failureStartMs = null
                pollInterval = 5_000L
                when (root["status"]?.asText()) {
                    "pending" -> {
                        val pos = root["queue_position"]?.takeIf { !it.isNull }?.asInt()
                        val posStr = if (pos != null) " Position: $pos." else ""
                        onProgress?.invoke("⏳ Waiting in queue...$posStr")
                    }
                    "processing" -> {
                        val elapsed = root["elapsed_s"]?.takeIf { !it.isNull }?.asDouble()
                        val elapsedStr = if (elapsed != null) " | ${fmtTime(elapsed)} elapsed" else ""
                        val prog = root["progress"]
                        if (prog != null && prog.isObject) {
                            val phase = prog["phase"]?.asText()
                            val summarizeNote = if (summaryMode != SummaryMode.OFF) "\n✍️ Summarization to follow" else ""
                            when (phase) {
                                "diarizing" -> {
                                    val diarizeProgress = prog["diarize_progress"]?.takeIf { !it.isNull }?.asDouble()
                                    val bar = if (diarizeProgress != null)
                                        "\n${progressBar(diarizeProgress)} ${(diarizeProgress * 100).toInt()}%"
                                    else ""
                                    onProgress?.invoke("👥 Detecting speakers...$bar$summarizeNote$elapsedStr")
                                }
                                else -> {
                                    val processed = prog["processed_s"]?.asDouble()
                                    val total = prog["total_s"]?.asDouble()?.takeIf { it > 0 }
                                    if (processed != null && total != null) {
                                        val pct = (processed / total * 100).toInt()
                                        val bar = progressBar(processed / total)
                                        val diarizeNote = if (diarize) "\n👥 Speaker detection to follow" else ""
                                        onProgress?.invoke("🎙 Transcribing audio...\n$bar $pct% (${fmtTime(processed)} / ${fmtTime(total)})$diarizeNote$summarizeNote$elapsedStr")
                                    }
                                }
                            }
                        } else {
                            val pos = root["queue_position"]?.takeIf { !it.isNull }?.asInt()
                            if (pos != null) {
                                onProgress?.invoke("⏳ Waiting in queue... Position: $pos.")
                            } else {
                                onProgress?.invoke("🎙 Transcribing...")
                            }
                        }
                    }
                    "summarizing" -> onProgress?.invoke("✍️ Summarizing...")
                    "done" -> {
                        val result = root["result"]
                        val text = result["formatted_text"]?.asText() ?: ""
                        val summary = result["summary"]?.takeIf { !it.isNull }?.asText()
                        val duration = root["duration_s"]?.takeIf { !it.isNull }?.asDouble()
                        val audioDuration = result["audio_duration_s"]?.takeIf { !it.isNull }?.asDouble()
                        return TranscriptResult(text, duration, audioDuration, summary, jobId)
                    }
                    "failed" -> throw RuntimeException(root["error"]?.asText() ?: "Unknown error")
                }
            } catch (e: RuntimeException) {
                throw e  // "failed" status or permanent errors — propagate immediately
            } catch (e: Exception) {
                // Transient network/IO error — apply exponential backoff
                val now = System.currentTimeMillis()
                if (failureStartMs == null) failureStartMs = now
                if (now - failureStartMs!! > 10 * 60 * 1000L) {
                    throw RuntimeException("Server unavailable for 10 minutes")
                }
                pollInterval = minOf(pollInterval * 2, 300_000L)
                onProgress?.invoke("⚠️ Server temporarily unavailable, retrying...")
            }
        }
        throw RuntimeException("Timeout: transcription did not complete within 4 hours")
    }

    private fun progressBar(fraction: Double, width: Int = 10): String {
        val filled = (fraction * width).toInt().coerceIn(0, width)
        return "▓".repeat(filled) + "░".repeat(width - filled)
    }

    private fun fmtTime(s: Double): String {
        val min = (s / 60).toInt()
        val sec = (s % 60).toInt()
        return if (min > 0) "${min}m ${sec}s" else "${sec}s"
    }
}
