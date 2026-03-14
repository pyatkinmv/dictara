package com.dictara.gateway.model

enum class SummaryMode {
    OFF, AUTO, BRIEF, CONCISE, FULL;

    companion object {
        fun fromString(raw: String?): SummaryMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
    }
}
