package com.dictara.gateway.repository

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID>

interface AuthIdentityRepository : JpaRepository<AuthIdentityEntity, UUID> {
    fun findByProviderAndProviderUid(provider: String, providerUid: String): AuthIdentityEntity?

    @Query(
        value = "SELECT * FROM auth_identities WHERE provider = 'telegram' AND metadata->>'username' = :username LIMIT 1",
        nativeQuery = true,
    )
    fun findByTelegramUsername(@Param("username") username: String): AuthIdentityEntity?
}
