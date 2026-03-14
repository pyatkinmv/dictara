package com.dictara.gateway.model

data class Segment(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String? = null,
)
