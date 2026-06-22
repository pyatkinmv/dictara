package com.dictara.gateway.plan

import java.util.UUID

class NoLimitStrategy : PlanStrategy {
    override fun enforce(userId: UUID) {}
}
