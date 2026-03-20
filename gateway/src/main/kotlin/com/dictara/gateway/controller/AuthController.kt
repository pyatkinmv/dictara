package com.dictara.gateway.controller

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.LoginNotificationEntity
import com.dictara.gateway.entity.LoginTokenEntity
import com.dictara.gateway.repository.AuthIdentityRepository
import com.dictara.gateway.repository.LoginNotificationRepository
import com.dictara.gateway.repository.LoginTokenRepository
import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.service.UserService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
class AuthController(
    private val jwt: JwtTokenProvider,
    private val userService: UserService,
    private val loginTokenRepo: LoginTokenRepository,
    private val loginNotificationRepo: LoginNotificationRepository,
    private val authIdentityRepo: AuthIdentityRepository,
    private val mapper: ObjectMapper,
    @Value("\${telegram.bot-username}") private val botUsername: String,
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
        val identity = authIdentityRepo.findByProviderAndProviderUid("telegram", uid) ?: return
        val current = runCatching { mapper.readTree(identity.metadata ?: "{}") }.getOrNull()
        if (current?.get("bot_started")?.asBoolean() == true) return
        @Suppress("UNCHECKED_CAST")
        val updated = (mapper.convertValue(current, Map::class.java) as Map<String, Any?>).toMutableMap()
        updated["bot_started"] = true
        authIdentityRepo.save(AuthIdentityEntity(
            id = identity.id, user = identity.user,
            provider = identity.provider, providerUid = identity.providerUid,
            credentials = identity.credentials,
            metadata = mapper.writeValueAsString(updated),
            createdAt = identity.createdAt,
        ))
    }

    @PostMapping("/auth/login-by-username")
    fun loginByUsername(@RequestBody req: LoginByUsernameRequest): LoginByUsernameResponse {
        val username = req.telegramUsername.trimStart('@')
        val token = UUID.randomUUID()
        val identity = authIdentityRepo.findByTelegramUsername(username)
        val botStarted = identity?.let {
            runCatching { mapper.readTree(it.metadata ?: "{}").get("bot_started")?.asBoolean() }.getOrNull() == true
        } ?: false
        if (identity != null && botStarted) {
            val tokenEntity = loginTokenRepo.save(LoginTokenEntity(
                token = token,
                user = identity.user,
                expiresAt = Instant.now().plusSeconds(600),
            ))
            loginNotificationRepo.save(LoginNotificationEntity(
                loginToken = tokenEntity,
                chatId = identity.providerUid,
            ))
            return LoginByUsernameResponse(token.toString(), "notified", null)
        } else {
            loginTokenRepo.findAllByPendingUsernameAndConfirmedFalseAndRejectedFalse(username)
                .forEach { it.rejected = true; loginTokenRepo.save(it) }
            loginTokenRepo.save(LoginTokenEntity(
                token = token,
                pendingUsername = username,
                expiresAt = Instant.now().plusSeconds(600),
            ))
            return LoginByUsernameResponse(token.toString(), "unknown_user", "https://t.me/$botUsername")
        }
    }

    @GetMapping("/auth/pending-login-notifications")
    fun getPendingNotifications(): List<Map<String, String>> =
        loginNotificationRepo.findBySentFalse().map {
            mapOf(
                "id" to it.id.toString(),
                "chatId" to it.chatId,
                "token" to it.loginToken.token.toString(),
            )
        }

    @PostMapping("/auth/pending-login-notifications/{id}/ack")
    fun ackNotification(@PathVariable id: Long) {
        loginNotificationRepo.findById(id).ifPresent {
            it.sent = true
            loginNotificationRepo.save(it)
        }
    }

    @GetMapping("/auth/pending-login-for-username")
    fun getPendingLoginForUsername(@RequestParam username: String): Map<String, String>? {
        val token = loginTokenRepo.findFirstByPendingUsernameAndConfirmedFalseAndRejectedFalseOrderByCreatedAtDesc(username)
            ?: return null
        return mapOf("token" to token.token.toString())
    }

    @PostMapping("/auth/login-link/confirm-callback")
    fun confirmCallback(@RequestBody req: ConfirmCallbackRequest) {
        val entity = loginTokenRepo.findById(UUID.fromString(req.token))
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (entity.confirmed || entity.rejected || entity.expiresAt.isBefore(Instant.now()))
            throw ResponseStatusException(HttpStatus.GONE, "Token expired or already used")
        val user = userService.resolveByTelegramId(
            req.telegramUserId, req.telegramUsername, req.telegramFirstName, req.telegramLastName,
        )
        entity.user = user
        entity.confirmed = true
        loginTokenRepo.save(entity)
    }

    @PostMapping("/auth/login-link/reject")
    fun rejectLogin(@RequestBody body: Map<String, String>) {
        val entity = loginTokenRepo.findById(UUID.fromString(body["token"]!!))
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        entity.rejected = true
        loginTokenRepo.save(entity)
    }

    @GetMapping("/auth/login-link/{token}")
    fun pollLoginLink(@PathVariable token: String): LoginLinkStatusResponse {
        val entity = loginTokenRepo.findById(UUID.fromString(token))
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (entity.rejected)
            throw ResponseStatusException(HttpStatus.GONE, "Login rejected")
        if (entity.expiresAt.isBefore(Instant.now()))
            throw ResponseStatusException(HttpStatus.GONE, "Token expired")
        if (!entity.confirmed || entity.user == null)
            return LoginLinkStatusResponse(false, null, null)
        val jwtToken = jwt.issue(entity.user!!.id!!)
        return LoginLinkStatusResponse(true, jwtToken, entity.user!!.displayName)
    }
}
