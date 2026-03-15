package com.dictara.gateway.dto

data class ProgressInfo(
    val phase: String,
    val processedS: Double? = null,
    val totalS: Double? = null,
    val diarizeProgress: Double? = null,
)
