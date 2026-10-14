package com.dictara.gateway.adapter

import com.dictara.gateway.port.TelegramBotSimulator
import com.dictara.gateway.service.AuthService
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Outside the `local` profile the real Telegram bot does this work; the gateway itself does nothing. */
@Component
@Profile("!local")
class NoopTelegramBotSimulator : TelegramBotSimulator {
    override fun beforeLogin(username: String) {}
    override fun afterLogin(username: String, result: AuthService.LoginByUsernameResult) {}
}
