package com.dictara.gateway.controller

import com.dictara.gateway.client.TranscribeParams
import com.dictara.gateway.model.GatewayJob
import com.dictara.gateway.model.GatewayJobStatus
import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.service.OrchestratorService
import com.dictara.gateway.store.JobStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

// ── Response DTOs ─────────────────────────────────────────────────────────────

data class SubmitResponse(val jobId: String)

data class SegmentResponse(
    val start: Double,
    val end: Double,
    val text: String,
    val speaker: String?,
)

data class ResultResponse(
    val segments: List<SegmentResponse>,
    val formattedText: String,
    val summary: String?,
    val audioDurationS: Double?,
)

data class ProgressResponse(
    val phase: String,
    val processedS: Double?,
    val totalS: Double?,
    val diarizeProgress: Double?,
)

data class JobResponse(
    val jobId: String,
    val status: String,
    val progress: ProgressResponse?,
    val result: ResultResponse?,
    val durationS: Double?,
    val elapsedS: Double?,
    val error: String?,
)

// ── Controller ────────────────────────────────────────────────────────────────

@RestController
class TranscribeController(
    private val jobStore: JobStore,
    private val orchestrator: OrchestratorService,
) {

    @GetMapping("/health")
    fun health() = mapOf("status" to "ok")

    @PostMapping("/transcribe")
    fun transcribe(
        @RequestPart("file") file: MultipartFile,
        @RequestParam(defaultValue = "fast") model: String,
        @RequestParam(defaultValue = "auto") language: String,
        @RequestParam(defaultValue = "false") diarize: Boolean,
        @RequestParam(name = "num_speakers", required = false) numSpeakers: Int?,
        @RequestParam(name = "summary_mode", defaultValue = "auto") summaryMode: String,
    ): ResponseEntity<SubmitResponse> {
        val job = GatewayJob(UUID.randomUUID().toString())
        jobStore.put(job)

        val params = TranscribeParams(
            model = model,
            language = language,
            diarize = diarize,
            numSpeakers = numSpeakers,
            originalFileName = file.originalFilename ?: "audio.bin",
        )

        orchestrator.startJob(job, params, SummaryMode.fromString(summaryMode), file.bytes)

        return ResponseEntity.accepted().body(SubmitResponse(job.jobId))
    }

    @GetMapping("/jobs/{jobId}")
    fun getJob(@PathVariable jobId: String): ResponseEntity<JobResponse> {
        val job = jobStore.get(jobId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.toResponse())
    }

    private fun GatewayJob.toResponse(): JobResponse {
        val prog = progress?.let {
            ProgressResponse(it.phase, it.processedS, it.totalS, it.diarizeProgress)
        }
        val res = result?.let { r ->
            ResultResponse(
                segments = r.segments.map { s -> SegmentResponse(s.start, s.end, s.text, s.speaker) },
                formattedText = r.formattedText,
                summary = r.summary,
                audioDurationS = r.audioDurationS,
            )
        }
        return JobResponse(
            jobId = jobId,
            status = status.name.lowercase(),
            progress = prog,
            result = res,
            durationS = durationS,
            elapsedS = elapsedS,
            error = error,
        )
    }
}
