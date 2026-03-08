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

    fun summarize(text: String): String {
        val prompt = "Summarize the following transcript concisely. " +
            "Respond in the same language as the transcript. " +
            "Focus on main topics, key points, and any conclusions.\n\n$text"
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
