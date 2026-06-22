package com.dictara.gateway.plan

import java.util.UUID

interface PlanStrategy {
    fun enforce(userId: UUID)
}
