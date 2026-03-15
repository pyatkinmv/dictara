package com.dictara.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class TranscriptResult(
    val text: String,
    val durationSeconds: Double?,
    val audioDurationSeconds: Double? = null,
    val summary: String? = null,
)

class DictaraClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

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
        onProgress: ((String) -> Unit)? = null,
    ): TranscriptResult {
        val jobId = submitJob(audioFile, model, diarize, summaryMode, language, numSpeakers,
            telegramUserId, telegramUsername, telegramFirstName, telegramLastName)
        return pollJob(jobId, diarize, summaryMode, onProgress)
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
        val deadline = System.currentTimeMillis() + 4 * 60 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            val response = http.newCall(Request.Builder().url("$baseUrl/jobs/$jobId").get().build()).execute()
            val root = mapper.readTree(response.body?.string() ?: "{}")
            when (root["status"]?.asText()) {
                "processing" -> {
                    val elapsed = root["elapsed_s"]?.takeIf { !it.isNull }?.asDouble()
                    val elapsedStr = if (elapsed != null) " | ${fmtTime(elapsed)} elapsed" else ""
                    val prog = root["progress"]
                    if (prog != null && !prog.isNull) {
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
                    }
                }
                "summarizing" -> onProgress?.invoke("✍️ Summarizing...")
                "done" -> {
                    val result = root["result"]
                    val text = result["formatted_text"]?.asText() ?: ""
                    val summary = result["summary"]?.takeIf { !it.isNull }?.asText()
                    val duration = root["duration_s"]?.takeIf { !it.isNull }?.asDouble()
                    val audioDuration = result["audio_duration_s"]?.takeIf { !it.isNull }?.asDouble()
                    return TranscriptResult(text, duration, audioDuration, summary)
                }
                "failed" -> throw RuntimeException(root["error"]?.asText() ?: "Unknown error")
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
