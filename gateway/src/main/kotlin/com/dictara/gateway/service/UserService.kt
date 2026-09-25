package com.dictara.gateway.service

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.UserEntity
import com.dictara.gateway.repository.AuthIdentityRepository
import com.dictara.gateway.repository.UserRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
        val botStarted = existing?.metadata?.get("bot_started")?.asBoolean() == true
        val metaMap = mapOf("firstName" to telegramFirstName, "lastName" to telegramLastName, "username" to telegramUsername)
            .filterValues { it != null }
            .let { if (botStarted || uid != "anonymous") it + ("bot_started" to true) else it }
        val metadata: JsonNode = mapper.valueToTree(metaMap)

        if (existing != null) {
            val user = userRepo.findById(existing.userId).orElseThrow()
            user.displayName = displayName
            userRepo.save(user)
            authIdentityRepo.save(AuthIdentityEntity(
                id = existing.id, userId = existing.userId,
                provider = "telegram", providerUid = uid,
                credentials = existing.credentials, metadata = metadata, createdAt = existing.createdAt,
            ))
            return user
        }
        val user = userRepo.save(UserEntity(displayName = displayName))
        authIdentityRepo.save(AuthIdentityEntity(userId = user.id!!, provider = "telegram", providerUid = uid, metadata = metadata))
        return user
    }

    fun resolveAnonymous(): UserEntity = resolveByTelegramId(null, null, null, null)

    /** A brand new, disposable account for "try it without an account" — unlike [resolveAnonymous] (which is
     *  the old Telegram-bot fallback and, because of the `auth_identities` unique constraint, resolves every
     *  caller to the *same* one shared user), every call here creates a genuinely new user with its own
     *  identity, on the `anonymous` plan (a small lifetime cap, see `plans` table). Linking this account to a
     *  real one on a later real login is not implemented yet (docs/redesign-plan.md, plan item 13) — the data
     *  is simply left behind. */
    @Transactional
    fun createAnonymous(): UserEntity {
        val user = userRepo.save(UserEntity(displayName = "Guest", plan = "anonymous"))
        authIdentityRepo.save(AuthIdentityEntity(userId = user.id!!, provider = "anon", providerUid = UUID.randomUUID().toString()))
        return user
    }
}
