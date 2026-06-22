package com.dictara.gateway.plan

import com.dictara.gateway.entity.PlanEntity
import com.dictara.gateway.repository.PlanRepository
import com.dictara.gateway.repository.SubmissionRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PlanService(
    private val planRepo: PlanRepository,
    private val submissionRepo: SubmissionRepository,
) {
    fun enforce(userId: UUID) {
        val plan = planRepo.findByUserId(userId) ?: return
        strategyFor(plan).enforce(userId)
    }

    private fun strategyFor(plan: PlanEntity): PlanStrategy {
        val limit = plan.constraints["monthly_submissions"]?.asInt() ?: return NoLimitStrategy()
        return MonthlySubmissionLimitStrategy(limit, submissionRepo)
    }
}
