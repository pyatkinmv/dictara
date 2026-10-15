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
    /** [PlanRepository.findByUserId] can only come back null for a [userId] with no `users` row at all:
     *  `users.plan` is `NOT NULL REFERENCES plans(name)` (V18), so every real user always resolves to a real
     *  plan. Silently letting a request through here would turn "not a real user" into an unmetered bypass,
     *  so this fails loud instead — it should never be reachable from an authenticated caller. */
    fun enforce(userId: UUID) {
        val plan = planRepo.findByUserId(userId) ?: throw NoSuchElementException("No such user: $userId")
        strategyFor(plan).enforce(userId)
    }

    /** The plan name and usage to show a user, worked out by their own plan — a plan with no cap still reports
     *  how much was used, just with no denominator (see [NoLimitStrategy]). See [enforce] for why a missing
     *  plan means a missing user, not a missing plan. */
    fun info(userId: UUID): UserPlanInfo {
        val plan = planRepo.findByUserId(userId) ?: throw NoSuchElementException("No such user: $userId")
        return UserPlanInfo(plan.displayName, strategyFor(plan).usage(userId))
    }

    /**
     * How many times a user may regenerate the summary of one transcription (`regenerations_per_transcription` in the
     * plan's constraints): null for no limit, 0 for none. Like the other constraints, a plan without the key has no limit.
     */
    fun regenerationLimit(userId: UUID): Int? {
        val plan = planRepo.findByUserId(userId) ?: throw NoSuchElementException("No such user: $userId")
        return plan.constraints["regenerations_per_transcription"]?.takeIf { it.isInt }?.asInt()
    }

    private fun strategyFor(plan: PlanEntity): PlanStrategy {
        plan.constraints["lifetime_submissions"]?.asInt()?.let { return LifetimeSubmissionLimitStrategy(it, submissionRepo) }
        val limit = plan.constraints["monthly_submissions"]?.asInt() ?: return NoLimitStrategy(submissionRepo)
        return MonthlySubmissionLimitStrategy(limit, submissionRepo)
    }
}
