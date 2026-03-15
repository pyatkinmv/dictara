package com.dictara.gateway.service

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.AuthIdentityRepository
import com.dictara.gateway.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepo: UserRepository,
    private val authIdentityRepo: AuthIdentityRepository,
    private val mapper: ObjectMapper,
) {
    @Transactional
    fun resolveByTelegramId(
        telegramUserId: String?,
        telegramUsername: String?,
        telegramFirstName: String?,
        telegramLastName: String?,
    ): UserEntity {
        val uid = telegramUserId ?: "anonymous"
        val displayName = when {
            !telegramUsername.isNullOrBlank() -> "@$telegramUsername"
            !telegramFirstName.isNullOrBlank() && !telegramLastName.isNullOrBlank() ->
                "$telegramFirstName $telegramLastName".trim()
            !telegramFirstName.isNullOrBlank() -> telegramFirstName
            else -> uid
        }
        val existing = authIdentityRepo.findByProviderAndProviderUid("telegram", uid)
        val botStarted = existing?.let {
            runCatching { mapper.readTree(it.metadata ?: "{}").get("bot_started")?.asBoolean() }.getOrNull() == true
        } ?: false
        val metaMap = mapOf("firstName" to telegramFirstName, "lastName" to telegramLastName, "username" to telegramUsername)
            .filterValues { it != null }
            .let { if (botStarted) it + ("bot_started" to true) else it }
        val metadata = mapper.writeValueAsString(metaMap)

        if (existing != null) {
            existing.user.displayName = displayName
            userRepo.save(existing.user)
            authIdentityRepo.save(AuthIdentityEntity(
                id = existing.id, user = existing.user,
                provider = "telegram", providerUid = uid,
                credentials = existing.credentials, metadata = metadata, createdAt = existing.createdAt,
            ))
            return existing.user
        }
        val user = userRepo.save(UserEntity(displayName = displayName))
        authIdentityRepo.save(AuthIdentityEntity(user = user, provider = "telegram", providerUid = uid, metadata = metadata))
        return user
    }

    fun resolveAnonymous(): UserEntity = resolveByTelegramId(null, null, null, null)
}
