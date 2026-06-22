package com.dictara.gateway.repository

import com.dictara.gateway.entity.LoginTokenEntity
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface LoginTokenRepository : CrudRepository<LoginTokenEntity, UUID> {
    fun findFirstByPendingUsernameAndConfirmedFalseAndRejectedFalseOrderByCreatedAtDesc(pendingUsername: String): LoginTokenEntity?
    fun findAllByPendingUsernameAndConfirmedFalseAndRejectedFalse(pendingUsername: String): List<LoginTokenEntity>
}
