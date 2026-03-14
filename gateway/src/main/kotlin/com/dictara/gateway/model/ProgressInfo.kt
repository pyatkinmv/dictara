package com.dictara.gateway.model

data class ProgressInfo(
    val phase: String,                   // "transcribing" | "diarizing" | "summarizing"
    val processedS: Double? = null,
    val totalS: Double? = null,
    val diarizeProgress: Double? = null,
)
