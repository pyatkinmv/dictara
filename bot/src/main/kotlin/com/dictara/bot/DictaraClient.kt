package com.dictara.bot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class TranscriptResult(val text: String, val durationSeconds: Double?)

val MODEL_ALIASES = mapOf("fast" to "small", "accurate" to "large-v3")

class DictaraClient(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

    fun transcribe(audioFile: File, modelAlias: String, diarize: Boolean, summarize: Boolean = false,
                   onProgress: ((String) -> Unit)? = null): TranscriptResult {
        val modelName = MODEL_ALIASES[modelAlias] ?: modelAlias
        val jobId = submitJob(audioFile, modelName, diarize)
        return pollJob(jobId, diarize, summarize, onProgress)
    }

    private fun submitJob(file: File, model: String, diarize: Boolean): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("$baseUrl/transcribe?model=$model&diarize=$diarize")
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw RuntimeException("Submit failed (${response.code}): $responseBody")
        }
        return mapper.readTree(responseBody)["job_id"].asText()
    }

    private fun pollJob(jobId: String, diarize: Boolean, summarize: Boolean, onProgress: ((String) -> Unit)? = null): TranscriptResult {
        val deadline = System.currentTimeMillis() + 4 * 60 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000)
            val request = Request.Builder()
                .url("$baseUrl/jobs/$jobId")
                .get()
                .build()
            val response = http.newCall(request).execute()
            val root = mapper.readTree(response.body?.string() ?: "{}")
            when (root["status"]?.asText()) {
                "processing" -> {
                    val elapsed = root["elapsed_s"]?.takeIf { !it.isNull }?.asDouble()
                    val elapsedStr = if (elapsed != null) " | ${fmtTime(elapsed)} elapsed" else ""
                    val prog = root["progress"]
                    if (prog != null && !prog.isNull) {
                        val phase = prog["phase"]?.asText()
                        val summarizeNote = if (summarize) "\n✍️ Summarization to follow" else ""
                        if (phase == "diarizing") {
                            val diarizeProgress = prog["diarize_progress"]?.takeIf { !it.isNull }?.asDouble()
                            val bar = if (diarizeProgress != null)
                                "\n${progressBar(diarizeProgress)} ${(diarizeProgress * 100).toInt()}%"
                            else ""
                            onProgress?.invoke("👥 Detecting speakers...$bar$summarizeNote$elapsedStr")
                        } else {
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
                "done" -> {
                    val segments = root["result"]["segments"]
                    val duration = root["duration_s"]?.takeIf { !it.isNull }?.asDouble()
                    return TranscriptResult(formatSegments(segments), duration)
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

    private fun formatTimestamp(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }

    private fun formatSegments(segments: JsonNode): String {
        return segments.joinToString("\n") { seg ->
            val start = seg["start"].asDouble()
            val end = seg["end"].asDouble()
            val text = seg["text"].asText()
            val speaker = seg["speaker"]?.takeIf { !it.isNull }?.asText()
            val ts = "[${formatTimestamp(start)} --> ${formatTimestamp(end)}]"
            if (speaker != null) "$ts [$speaker] $text" else "$ts $text"
        }
    }
}
