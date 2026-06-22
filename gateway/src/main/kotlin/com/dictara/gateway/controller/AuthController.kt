package com.dictara.gateway.controller

import com.dictara.gateway.repository.LoginNotificationRepository
import com.dictara.gateway.repository.LoginTokenRepository
import com.dictara.gateway.service.AuthService
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class AuthController(
    private val authService: AuthService,
    private val loginTokenRepo: LoginTokenRepository,
    private val loginNotificationRepo: LoginNotificationRepository,
) {
    data class LoginByUsernameRequest(val telegramUsername: String)
    data class LoginByUsernameResponse(val token: String, val status: String, val botUrl: String?)

    data class ConfirmCallbackRequest(
        val token: String,
        val telegramUserId: String,
        val telegramUsername: String?,
        val telegramFirstName: String?,
        val telegramLastName: String?,
    )

    data class LoginLinkStatusResponse(val confirmed: Boolean, val token: String?, val displayName: String?)

    @PostMapping("/auth/bot-started")
    fun markBotStarted(@RequestBody body: Map<String, String>) {
        val uid = body["telegram_user_id"] ?: return
        authService.markBotStarted(uid)
    }

    @PostMapping("/auth/login-by-username")
    fun loginByUsername(@RequestBody req: LoginByUsernameRequest): LoginByUsernameResponse {
        val result = authService.loginByUsername(req.telegramUsername.trimStart('@'))
        return LoginByUsernameResponse(result.token.toString(), result.status, result.botUrl)
    }

    @GetMapping("/auth/pending-login-notifications")
    fun getPendingNotifications(): List<Map<String, String>> =
        loginNotificationRepo.findBySentFalse().map {
            mapOf("id" to it.id.toString(), "chatId" to it.chatId, "token" to it.tokenId.toString())
        }

    @PostMapping("/auth/pending-login-notifications/{id}/ack")
    fun ackNotification(@PathVariable id: Long) = authService.ackNotification(id)

    @GetMapping("/auth/pending-login-for-username")
    fun getPendingLoginForUsername(@RequestParam username: String): Map<String, String>? {
        val token = loginTokenRepo.findFirstByPendingUsernameAndConfirmedFalseAndRejectedFalseOrderByCreatedAtDesc(username)
            ?: return null
        return mapOf("token" to token.token.toString())
    }

    @PostMapping("/auth/login-link/confirm-callback")
    fun confirmCallback(@RequestBody req: ConfirmCallbackRequest) =
        authService.confirmCallback(UUID.fromString(req.token), req.telegramUserId, req.telegramUsername, req.telegramFirstName, req.telegramLastName)

    @PostMapping("/auth/login-link/reject")
    fun rejectLogin(@RequestBody body: Map<String, String>) =
        authService.rejectLogin(UUID.fromString(body["token"]!!))

    @GetMapping("/auth/login-link/{token}")
    fun pollLoginLink(@PathVariable token: String): LoginLinkStatusResponse {
        val result = authService.pollLoginLink(UUID.fromString(token))
        return LoginLinkStatusResponse(result.confirmed, result.jwtToken, result.displayName)
    }
}
