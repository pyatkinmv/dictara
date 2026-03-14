package com.dictara.gateway.port

import com.dictara.gateway.model.SummaryMode

interface SummarizerPort {
    fun isAvailable(): Boolean
    fun summarize(text: String, audioDurationSeconds: Double?, mode: SummaryMode, language: String): String
}
