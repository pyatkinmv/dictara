package com.dictara.gateway.plan

import com.dictara.gateway.entity.PlanEntity
import com.dictara.gateway.repository.PlanRepository
import com.dictara.gateway.repository.SubmissionRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** What the profile panel shows for a user's plan (`GET /users/me`). */
data class UserPlanInfo(val planDisplayName: String, val usage: PlanUsage)

@Service
class PlanService(
    private val planRepo: PlanRepository,
    private val submissionRepo: SubmissionRepository,
) {
    fun enforce(userId: UUID) {
        val plan = planRepo.findByUserId(userId) ?: return
        strategyFor(plan).enforce(userId)
    }

    /** The plan name and usage to show a user, worked out by their own plan — a plan with no cap still reports
     *  how much was used, just with no denominator (see [NoLimitStrategy]). */
    fun info(userId: UUID): UserPlanInfo {
        val plan = planRepo.findByUserId(userId)
            ?: return UserPlanInfo("Free", PlanUsage(used = 0, max = null, unit = "transcriptions"))
        return UserPlanInfo(plan.displayName, strategyFor(plan).usage(userId))
    }

    private fun strategyFor(plan: PlanEntity): PlanStrategy {
        val limit = plan.constraints["monthly_submissions"]?.asInt() ?: return NoLimitStrategy(submissionRepo)
        return MonthlySubmissionLimitStrategy(limit, submissionRepo)
    }
}
