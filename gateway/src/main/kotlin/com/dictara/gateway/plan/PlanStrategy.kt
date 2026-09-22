package com.dictara.gateway.plan

import java.util.UUID

/** How much of a plan's monthly allowance has been used (`GET /users/me`).
 *  [max] is null for a plan with no cap on [unit] — the client then shows a count with no denominator and no bar. */
data class PlanUsage(val used: Int, val max: Int?, val unit: String)

interface PlanStrategy {
    fun enforce(userId: UUID)

    /** The usage to show for [userId], whether or not this plan enforces a cap on it. */
    fun usage(userId: UUID): PlanUsage
}
