package com.dictara.gateway.repository

import com.dictara.gateway.entity.PlanEntity
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.util.UUID

interface PlanRepository : Repository<PlanEntity, String> {
    @Query("SELECT p.* FROM plans p JOIN users u ON u.plan = p.name WHERE u.id = :userId")
    fun findByUserId(userId: UUID): PlanEntity?
}
