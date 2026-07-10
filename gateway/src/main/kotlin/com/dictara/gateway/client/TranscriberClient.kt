package com.dictara.gateway.client

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.model.Segment
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdTokenCredentials
import com.google.auth.oauth2.IdTokenProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.stereotype.Component
import java.net.URLEncoder
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
    val detectedLanguage: String?,
    val durationS: Double?,
    val elapsedS: Double?,
    val error: String?,
    val retryable: Boolean = true,
)

private val MODEL_ALIASES = mapOf("fast" to "small", "accurate" to "turbo")

@Component
class TranscriberClient(private val props: DictaraProperties) {

    private val idTokenCredentials: IdTokenCredentials? =
        if (props.transcriber.url.startsWith("https://")) {
            val base = props.transcriber.url.trimEnd('/')
            val creds = GoogleCredentials.getApplicationDefault()
            IdTokenCredentials.newBuilder()
                .setIdTokenProvider(creds as IdTokenProvider)
                .setTargetAudience(base)
                .build()
        } else null

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .apply {
            if (idTokenCredentials != null) {
                addInterceptor { chain ->
                    idTokenCredentials.refreshIfExpired()
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Authorization", "Bearer ${idTokenCredentials.idToken.tokenValue}")
                            .build()
                    )
                }
            }
        }
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

    /** Submits a job by streaming the raw file bytes — the local-dev fallback path used
     *  when no GCS bucket is configured (`dictara.storage.gcs.bucket` empty). On Cloud Run
     *  this hits a hard 32 MiB request body limit; see [submitByReference] for the GCS path. */
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

    /** Submits a job by GCS reference instead of streaming bytes — required when the
     *  transcriber runs on Cloud Run, which enforces a hard 32 MiB request body limit
     *  (see docs/cloud-run-migration.md). The transcriber downloads the object directly
     *  from GCS via [storageUri], so the request body stays empty regardless of file size.
     *  See [submit] for the legacy multipart path used as a local-dev fallback when no
     *  GCS bucket is configured. */
    fun submitByReference(storageUri: String, params: TranscribeParams): String {
        val modelName = MODEL_ALIASES[params.model] ?: params.model
        val url = buildString {
            append("${props.transcriber.url}/transcribe?model=$modelName&diarize=${params.diarize}")
            append("&storage_uri=${URLEncoder.encode(storageUri, "UTF-8")}")
            if (params.language != "auto") append("&language=${params.language}")
            if (params.numSpeakers != null) append("&num_speakers=${params.numSpeakers}")
        }

        val response = http.newCall(Request.Builder().url(url).post("".toRequestBody(null)).build()).execute()
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
        val progress = root["progress"]?.takeIf { it.isObject }?.let { prog ->
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
        val detectedLanguage = root["result"]?.get("language")?.takeIf { !it.isNull }?.asText()
        val durationS = root["duration_s"]?.takeIf { !it.isNull }?.asDouble()
        val elapsedS = root["elapsed_s"]?.takeIf { !it.isNull }?.asDouble()
        val error = root["error"]?.takeIf { !it.isNull }?.asText()
        val retryable = root["retryable"]?.takeIf { !it.isNull }?.asBoolean() ?: true

        return TranscriberJobSnapshot(status, progress, segments, audioDurationS, detectedLanguage, durationS, elapsedS, error, retryable)
    }
}
