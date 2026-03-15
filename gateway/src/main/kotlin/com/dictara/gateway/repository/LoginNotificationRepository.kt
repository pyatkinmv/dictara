package com.dictara.gateway.repository

import com.dictara.gateway.entity.LoginNotificationEntity
import org.springframework.data.jpa.repository.JpaRepository

interface LoginNotificationRepository : JpaRepository<LoginNotificationEntity, Long> {
    fun findBySentFalse(): List<LoginNotificationEntity>
}
