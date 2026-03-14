package com.dictara.gateway.model

data class JobResult(
    val segments: List<Segment>,
    val formattedText: String,
    val summary: String? = null,
    val audioDurationS: Double? = null,
)
