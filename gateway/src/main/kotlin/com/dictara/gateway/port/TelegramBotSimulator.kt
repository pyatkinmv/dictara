package com.dictara.gateway.port

import com.dictara.gateway.service.AuthService

/**
 * Stands in for the real Telegram bot around `AuthController.loginByUsername`: in production this does
 * nothing (the bot is a separate service — it sends the confirmation message and calls back on its own);
 * under the `local` profile it answers for itself, so the login screen works without a running bot or a real
 * Telegram account. See `LocalTelegramBotSimulator`.
 */
interface TelegramBotSimulator {
    /** Called with the trimmed username before it is looked up. */
    fun beforeLogin(username: String)

    /** Called after, with what `AuthService.loginByUsername` answered for it. */
    fun afterLogin(username: String, result: AuthService.LoginByUsernameResult)
}
