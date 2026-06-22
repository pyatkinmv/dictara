package com.dictara.gateway.repository

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.UserEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface UserRepository : CrudRepository<UserEntity, UUID>

interface AuthIdentityRepository : CrudRepository<AuthIdentityEntity, UUID> {
    fun findByProviderAndProviderUid(provider: String, providerUid: String): AuthIdentityEntity?

    @Query("SELECT * FROM auth_identities WHERE provider = 'telegram' AND metadata->>'username' = :username LIMIT 1")
    fun findByTelegramUsername(username: String): AuthIdentityEntity?
}
