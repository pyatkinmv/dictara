package com.dictara.gateway.plan

import com.dictara.gateway.repository.SubmissionRepository
import java.util.UUID

/** A plan with nothing in `constraints`: nothing is enforced, but the count for the month is still worth
 *  showing the user (`GET /users/me`), just without a denominator. */
class NoLimitStrategy(private val submissionRepo: SubmissionRepository) : PlanStrategy {
    override fun enforce(userId: UUID) {}

    override fun usage(userId: UUID) =
        PlanUsage(used = submissionRepo.countCurrentMonthSubmissions(userId).toInt(), max = null, unit = "transcriptions")
}
