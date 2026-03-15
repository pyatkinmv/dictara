package com.dictara.gateway.repository

import com.dictara.gateway.entity.AuthIdentityEntity
import com.dictara.gateway.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<UserEntity, UUID>

interface AuthIdentityRepository : JpaRepository<AuthIdentityEntity, UUID> {
    fun findByProviderAndProviderUid(provider: String, providerUid: String): AuthIdentityEntity?
}
