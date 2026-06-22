package com.dictara.gateway.repository

import com.dictara.gateway.entity.LoginNotificationEntity
import org.springframework.data.repository.CrudRepository

interface LoginNotificationRepository : CrudRepository<LoginNotificationEntity, Long> {
    fun findBySentFalse(): List<LoginNotificationEntity>
}
