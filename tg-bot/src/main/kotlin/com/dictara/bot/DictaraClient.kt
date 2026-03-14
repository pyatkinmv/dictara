package com.dictara.bot

import com.dictara.generated.models.JobResponse
import com.dictara.generated.models.SubmitResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
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

    fun transcribe(
        audioFile: File,
        model: String,
        diarize: Boolean,
        summaryMode: SummaryMode = SummaryMode.AUTO,
        language: String = "auto",
        numSpeakers: Int? = null,
        onProgress: ((String) -> Unit)? = null,
    ): TranscriptResult {
        val jobId = submitJob(audioFile, model, diarize, summaryMode, language, numSpeakers)
        return pollJob(jobId, diarize, summaryMode, onProgress)
    }

    private fun submitJob(
        file: File,
        model: String,
        diarize: Boolean,
        summaryMode: SummaryMode,
        language: String,
        numSpeakers: Int?,
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

        val response = http.newCall(Request.Builder().url(url).post(body).build()).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) throw RuntimeException("Submit failed (${response.code}): $responseBody")
        return mapper.readValue(responseBody, SubmitResponse::class.java).jobId
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
            val jobResp = mapper.readValue(response.body?.string() ?: "{}", JobResponse::class.java)

            when (jobResp.status) {
                "processing" -> {
                    val elapsedStr = jobResp.elapsedS?.let { " | ${fmtTime(it)} elapsed" } ?: ""
                    val prog = jobResp.progress
                    if (prog != null) {
                        val summarizeNote = if (summaryMode != SummaryMode.OFF) "\n✍️ Summarization to follow" else ""
                        when (prog.phase) {
                            "diarizing" -> {
                                val bar = prog.diarizeProgress?.let {
                                    "\n${progressBar(it)} ${(it * 100).toInt()}%"
                                } ?: ""
                                onProgress?.invoke("👥 Detecting speakers...$bar$summarizeNote$elapsedStr")
                            }
                            else -> {
                                val processed = prog.processedS
                                val total = prog.totalS?.takeIf { it > 0 }
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
                    val result = jobResp.result
                    return TranscriptResult(
                        text = result?.formattedText ?: "",
                        durationSeconds = jobResp.durationS,
                        audioDurationSeconds = result?.audioDurationS,
                        summary = result?.summary,
                    )
                }
                "failed" -> throw RuntimeException(jobResp.error ?: "Unknown error")
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
