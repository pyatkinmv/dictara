package com.dictara.gateway.adapter

import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.SummarizerPort

class NoopSummarizer : SummarizerPort {
    override fun isAvailable() = false
    override fun analyze(input: AnalysisInput) = Analysis(summary = "")
}
