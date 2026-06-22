package com.dictara.gateway.service

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.LoginNotificationEntity
import com.dictara.gateway.entity.LoginTokenEntity
import com.dictara.gateway.repository.AuthIdentityRepository
import com.dictara.gateway.repository.LoginNotificationRepository
import com.dictara.gateway.repository.LoginTokenRepository
import com.dictara.gateway.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val authIdentityRepo: AuthIdentityRepository,
    private val loginTokenRepo: LoginTokenRepository,
    private val loginNotificationRepo: LoginNotificationRepository,
    private val userRepo: UserRepository,
    private val userService: UserService,
    private val jwt: JwtTokenProvider,
    private val mapper: ObjectMapper,
    @Value("\${telegram.bot-username}") private val botUsername: String,
) {
    data class LoginByUsernameResult(val token: UUID, val status: String, val botUrl: String?)
    data class LoginLinkStatus(val confirmed: Boolean, val jwtToken: String?, val displayName: String?)

    @Transactional
    fun markBotStarted(telegramUserId: String) {
        val identity = authIdentityRepo.findByProviderAndProviderUid("telegram", telegramUserId) ?: return
        val current = (identity.metadata?.deepCopy() as? ObjectNode) ?: mapper.createObjectNode()
        if (current.get("bot_started")?.asBoolean() == true) return
        current.put("bot_started", true)
        authIdentityRepo.save(AuthIdentityEntity(
            id = identity.id, userId = identity.userId,
            provider = identity.provider, providerUid = identity.providerUid,
            credentials = identity.credentials, metadata = current,
            createdAt = identity.createdAt,
        ))
    }

    @Transactional
    fun loginByUsername(username: String): LoginByUsernameResult {
        val token = UUID.randomUUID()
        val identity = authIdentityRepo.findByTelegramUsername(username)
        val botStarted = identity?.metadata?.get("bot_started")?.asBoolean() == true
        if (identity != null && botStarted) {
            loginTokenRepo.save(LoginTokenEntity(
                token = token, userId = identity.userId,
                expiresAt = Instant.now().plusSeconds(600),
            ).apply { _isNew = true })
            loginNotificationRepo.save(LoginNotificationEntity(tokenId = token, chatId = identity.providerUid))
            return LoginByUsernameResult(token, "notified", null)
        } else {
            loginTokenRepo.findAllByPendingUsernameAndConfirmedFalseAndRejectedFalse(username)
                .forEach { it.rejected = true; loginTokenRepo.save(it) }
            loginTokenRepo.save(LoginTokenEntity(
                token = token, pendingUsername = username,
                expiresAt = Instant.now().plusSeconds(600),
            ).apply { _isNew = true })
            return LoginByUsernameResult(token, "unknown_user", "https://t.me/$botUsername")
        }
    }

    @Transactional
    fun ackNotification(id: Long) {
        loginNotificationRepo.findById(id).ifPresent {
            it.sent = true
            loginNotificationRepo.save(it)
        }
    }

    @Transactional
    fun confirmCallback(token: UUID, telegramUserId: String, telegramUsername: String?, telegramFirstName: String?, telegramLastName: String?) {
        val entity = loginTokenRepo.findById(token)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (entity.confirmed || entity.rejected || entity.expiresAt.isBefore(Instant.now()))
            throw ResponseStatusException(HttpStatus.GONE, "Token expired or already used")
        val user = userService.resolveByTelegramId(telegramUserId, telegramUsername, telegramFirstName, telegramLastName)
        entity.userId = user.id
        entity.confirmed = true
        loginTokenRepo.save(entity)
    }

    @Transactional
    fun rejectLogin(token: UUID) {
        val entity = loginTokenRepo.findById(token)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        entity.rejected = true
        loginTokenRepo.save(entity)
    }

    @Transactional(readOnly = true)
    fun pollLoginLink(token: UUID): LoginLinkStatus {
        val entity = loginTokenRepo.findById(token)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        if (entity.rejected) throw ResponseStatusException(HttpStatus.GONE, "Login rejected")
        if (entity.expiresAt.isBefore(Instant.now())) throw ResponseStatusException(HttpStatus.GONE, "Token expired")
        if (!entity.confirmed || entity.userId == null) return LoginLinkStatus(false, null, null)
        val user = userRepo.findById(entity.userId!!).orElseThrow()
        return LoginLinkStatus(true, jwt.issue(entity.userId!!), user.displayName)
    }
}
