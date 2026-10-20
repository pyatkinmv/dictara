package com.dictara.gateway.adapter

import com.dictara.gateway.guard.PaidOperation
import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.SummarizerPort

open class NoopSummarizer : SummarizerPort {
    override fun isAvailable() = false
    @PaidOperation
    override fun analyze(input: AnalysisInput) = Analysis(summary = "")
}
