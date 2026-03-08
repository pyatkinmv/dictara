package com.dictara.bot

import java.util.concurrent.ConcurrentHashMap

data class UserPrefs(
    val model: String = "accurate",
    val diarize: Boolean = true,
    val summarize: Boolean = true,
)

object UserSettings {
    private val store = ConcurrentHashMap<Long, UserPrefs>()

    fun get(userId: Long): UserPrefs = store.getOrDefault(userId, UserPrefs())

    fun update(userId: Long, model: String? = null, diarize: Boolean? = null, summarize: Boolean? = null) {
        val current = get(userId)
        store[userId] = current.copy(
            model = model ?: current.model,
            diarize = diarize ?: current.diarize,
            summarize = summarize ?: current.summarize,
        )
    }
}
