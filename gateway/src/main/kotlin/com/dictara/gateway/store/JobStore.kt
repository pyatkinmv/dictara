package com.dictara.gateway.store

import com.dictara.gateway.model.GatewayJob
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class JobStore {
    private val jobs = ConcurrentHashMap<String, GatewayJob>()

    fun put(job: GatewayJob) { jobs[job.jobId] = job }

    fun get(jobId: String): GatewayJob? = jobs[jobId]
}
