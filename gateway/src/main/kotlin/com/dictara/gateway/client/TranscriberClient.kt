package com.dictara.gateway.client

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.model.Segment
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

data class TranscribeParams(
    val model: String,
    val language: String,
    val diarize: Boolean,
    val numSpeakers: Int?,
    val originalFileName: String,
)

data class TranscriberJobSnapshot(
    val status: String,
    val progress: ProgressInfo?,
    val segments: List<Segment>?,
    val audioDurationS: Double?,
    val durationS: Double?,
    val elapsedS: Double?,
    val error: String?,
)

private val MODEL_ALIASES = mapOf("fast" to "small", "accurate" to "large-v3")

@Component
class TranscriberClient(private val props: DictaraProperties) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

    fun submit(fileBytes: ByteArray, fileName: String, params: TranscribeParams): String {
        val modelName = MODEL_ALIASES[params.model] ?: params.model
        val url = buildString {
            append("${props.transcriber.url}/transcribe?model=$modelName&diarize=${params.diarize}")
            if (params.language != "auto") append("&language=${params.language}")
            if (params.numSpeakers != null) append("&num_speakers=${params.numSpeakers}")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        val response = http.newCall(Request.Builder().url(url).post(body).build()).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) throw RuntimeException("Transcriber submit failed (${response.code}): $responseBody")
        return mapper.readTree(responseBody)["job_id"].asText()
    }

    fun getJob(jobId: String): TranscriberJobSnapshot {
        val response = http.newCall(
            Request.Builder().url("${props.transcriber.url}/jobs/$jobId").get().build()
        ).execute()
        val root = mapper.readTree(response.body?.string() ?: "{}")

        val status = root["status"]?.asText() ?: "pending"
        val progress = root["progress"]?.takeIf { !it.isNull }?.let { prog ->
            ProgressInfo(
                phase = prog["phase"]?.asText() ?: "transcribing",
                processedS = prog["processed_s"]?.takeIf { !it.isNull }?.asDouble(),
                totalS = prog["total_s"]?.takeIf { !it.isNull }?.asDouble(),
                diarizeProgress = prog["diarize_progress"]?.takeIf { !it.isNull }?.asDouble(),
            )
        }

        val segments = if (status == "done") {
            root["result"]?.get("segments")?.map { seg ->
                Segment(
                    start = seg["start"].asDouble(),
                    end = seg["end"].asDouble(),
                    text = seg["text"].asText(),
                    speaker = seg["speaker"]?.takeIf { !it.isNull }?.asText(),
                )
            }
        } else null

        val audioDurationS = root["result"]?.get("audio_duration_s")?.takeIf { !it.isNull }?.asDouble()
        val durationS = root["duration_s"]?.takeIf { !it.isNull }?.asDouble()
        val elapsedS = root["elapsed_s"]?.takeIf { !it.isNull }?.asDouble()
        val error = root["error"]?.takeIf { !it.isNull }?.asText()

        return TranscriberJobSnapshot(status, progress, segments, audioDurationS, durationS, elapsedS, error)
    }
}
