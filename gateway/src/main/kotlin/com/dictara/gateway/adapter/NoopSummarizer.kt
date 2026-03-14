package com.dictara.gateway.adapter

import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.SummarizerPort

class NoopSummarizer : SummarizerPort {
    override fun isAvailable() = false
    override fun summarize(text: String, audioDurationSeconds: Double?, mode: SummaryMode, language: String) = ""
}
