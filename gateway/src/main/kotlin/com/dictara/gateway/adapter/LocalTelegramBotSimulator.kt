package com.dictara.gateway.adapter

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.port.TelegramBotSimulator
import com.dictara.gateway.service.AuthService
import com.dictara.gateway.service.UserService
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Stands in for the real Telegram bot during local development (`docs/local-backend-integration.md`,
 * "Итерация 10"): no bot process, no token, no real Telegram account needed to exercise the login screen.
 *
 *  - A username the local database has not seen before is registered on the spot, as if that person had
 *    already talked to the bot, so the very first `POST /auth/login-by-username` finds it and answers
 *    "notified" instead of "unknown_user" ([beforeLogin]).
 *  - A short pause later — long enough for the "Check your Telegram" screen to be seen —
 *    it "taps Confirm" itself ([afterLogin]).
 *  - The one exception is the username "unknown" (any case): left unregistered on purpose, so the real code
 *    takes the other branch and answers "unknown_user" — the screen for someone who has not started the bot.
 *
 * The Telegram id used is worked out from the username (`local-<username>`), so logging in again under the
 * same name returns to the same account, and a different name is a different, brand-new one — the same as it
 * would be in production.
 */
@Component
@Profile("local")
class LocalTelegramBotSimulator(
    private val userService: UserService,
    private val authService: AuthService,
    props: DictaraProperties,
) : TelegramBotSimulator {

    companion object {
        /** Typing this username (any case) is never registered, so the login screen can be tested against
         *  someone who has not started the bot. */
        const val UNKNOWN_USERNAME = "unknown"
    }

    private val delayMs = (props.local.stub.loginConfirmSeconds * 1000).toLong()

    // A single daemon thread: this is a handful of delayed calls, not a job queue.
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "local-telegram-bot-stub").apply { isDaemon = true } }

    override fun beforeLogin(username: String) {
        if (username.equals(UNKNOWN_USERNAME, ignoreCase = true)) return
        userService.resolveByTelegramId(telegramUserId(username), username, null, null)
    }

    override fun afterLogin(username: String, result: AuthService.LoginByUsernameResult) {
        if (result.status != "notified") return
        scheduler.schedule(
            { runCatching { authService.confirmCallback(result.token, telegramUserId(username), username, null, null) } },
            delayMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun telegramUserId(username: String) = "local-${username.lowercase()}"
}
