package com.dictara.bot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient {
    private val apiKey = System.getenv("GEMINI_API_KEY") ?: ""
    private val model = System.getenv("GEMINI_MODEL") ?: "gemini-2.5-flash"
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = ObjectMapper().registerKotlinModule()

    fun isAvailable() = apiKey.isNotBlank()

    fun summarize(text: String, audioDurationSeconds: Double? = null): String {
        val durationHint = audioDurationSeconds?.let {
            val mins = (it / 60).toInt()
            val secs = (it % 60).toInt()
            if (mins > 0) "~$mins minute${if (mins != 1) "s" else ""}" else "~$secs seconds"
        } ?: "unknown duration"

        val prompt = """
            You are summarizing an audio transcript ($durationHint).
            Respond entirely in the same language as the transcript — including all section headers and labels.

            - Under 2 minutes: 1–2 sentences only. No headers, no structure.
            - 2 to 15 minutes: a concise paragraph (3–5 sentences) covering the main topic, key points, and conclusions.
            - Over 15 minutes: structured format with these sections (translate all headers to the transcript's language):

              📝 Summary — 2–3 sentence overview
              Key points:
              • ...
              Conclusions:
              • ...
              ✅ Action items: — include ONLY if specific tasks or follow-ups were explicitly mentioned

            Be concise and proportional to the content. Do not pad short content.

            Transcript:
            $text
        """.trimIndent()
        val requestBody = mapper.writeValueAsString(
            mapOf("contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))))
        ).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(requestBody)
            .build()
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) throw RuntimeException("Gemini error (${response.code}): $body")
        return mapper.readTree(body)["candidates"][0]["content"]["parts"][0]["text"].asText()
    }
}
