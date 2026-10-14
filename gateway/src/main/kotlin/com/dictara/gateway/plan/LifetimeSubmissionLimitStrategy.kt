package com.dictara.gateway.plan

import com.dictara.gateway.repository.SubmissionRepository
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** A hard cap on submissions ever, not per month — for the anonymous "try it" plan: the point is a single
 *  trial, not a recurring free tier that resets every month like [MonthlySubmissionLimitStrategy]. */
class LifetimeSubmissionLimitStrategy(
    private val limit: Int,
    private val submissionRepo: SubmissionRepository,
) : PlanStrategy {
    override fun enforce(userId: UUID) {
        val count = submissionRepo.countAllSubmissions(userId)
        if (count >= limit) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "You've used your free trial. Sign in with Telegram to keep transcribing.",
            )
        }
    }

    override fun usage(userId: UUID) =
        PlanUsage(used = submissionRepo.countAllSubmissions(userId).toInt(), max = limit, unit = "transcriptions")
}
