package com.dictara.bot

import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession

fun main() {
    val token = System.getenv("TELEGRAM_TOKEN")
        ?: error("TELEGRAM_TOKEN env var is required")
    val dictaraUrl = System.getenv("DICTARA_URL") ?: "http://localhost:8000"

    val api = TelegramBotsApi(DefaultBotSession::class.java)
    api.registerBot(DictaraBot(token, dictaraUrl))
    println("Dictara bot started.")
}
