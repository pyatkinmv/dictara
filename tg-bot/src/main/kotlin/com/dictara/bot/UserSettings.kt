package com.dictara.bot

import java.util.concurrent.ConcurrentHashMap

data class UserPrefs(
    val model: String = "accurate",
    val diarize: Boolean = true,
    val language: String = "auto",   // "auto" or ISO code like "en", "ru"
    val numSpeakers: Int? = null,    // null = auto-detect, 1-4 = exact count
    val summaryMode: SummaryMode = SummaryMode.AUTO,
)

object UserSettings {
    private val store = ConcurrentHashMap<Long, UserPrefs>()

    fun get(id: Long): UserPrefs = store.getOrDefault(id, UserPrefs())

    fun update(
        id: Long,
        model: String? = null,
        diarize: Boolean? = null,
        language: String? = null,
        numSpeakers: Int? = null,
        clearNumSpeakers: Boolean = false,
        summaryMode: SummaryMode? = null,
    ) {
        val current = get(id)
        store[id] = current.copy(
            model = model ?: current.model,
            diarize = diarize ?: current.diarize,
            language = language ?: current.language,
            numSpeakers = if (clearNumSpeakers) null else numSpeakers ?: current.numSpeakers,
            summaryMode = summaryMode ?: current.summaryMode,
        )
    }
}
