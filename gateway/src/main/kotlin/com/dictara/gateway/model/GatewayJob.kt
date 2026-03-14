package com.dictara.gateway.model

class GatewayJob(val jobId: String) {
    @Volatile var status: GatewayJobStatus = GatewayJobStatus.PENDING
    @Volatile var progress: ProgressInfo? = null
    @Volatile var result: JobResult? = null
    @Volatile var error: String? = null
    @Volatile var durationS: Double? = null
    @Volatile var elapsedS: Double? = null
    val createdAt: Long = System.currentTimeMillis()
    @Volatile var startedAt: Long? = null
    @Volatile var finishedAt: Long? = null
}
