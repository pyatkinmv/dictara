package com.dictara.bot

import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.bots.DefaultBotOptions
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

fun main() {
    val token = System.getenv("TELEGRAM_TOKEN")
        ?: error("TELEGRAM_TOKEN env var is required")
    val dictaraUrl = System.getenv("TRANSCRIBER_URL") ?: "http://localhost:8000"
    val apiUrl = System.getenv("TELEGRAM_API_URL")

    val options = DefaultBotOptions()
    if (apiUrl != null) {
        options.baseUrl = "$apiUrl/bot"
    }

    val api = TelegramBotsApi(DefaultBotSession::class.java)
    api.registerBot(DictaraBot(token, dictaraUrl, options))
    println("Dictara bot started.")
}
