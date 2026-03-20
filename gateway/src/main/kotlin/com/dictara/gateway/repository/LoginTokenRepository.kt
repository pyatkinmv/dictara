package com.dictara.gateway.repository

import com.dictara.gateway.entity.LoginTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface LoginTokenRepository : JpaRepository<LoginTokenEntity, UUID> {
    fun findFirstByPendingUsernameAndConfirmedFalseAndRejectedFalseOrderByCreatedAtDesc(pendingUsername: String): LoginTokenEntity?
    fun findAllByPendingUsernameAndConfirmedFalseAndRejectedFalse(pendingUsername: String): List<LoginTokenEntity>
}
