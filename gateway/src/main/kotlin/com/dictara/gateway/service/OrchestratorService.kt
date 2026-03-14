package com.dictara.gateway.service

import com.dictara.gateway.client.TranscribeParams
import com.dictara.gateway.client.TranscriberClient
import com.dictara.gateway.model.*
import com.dictara.gateway.port.SummarizerPort
import org.springframework.stereotype.Service
import java.util.concurrent.Executors

@Service
class OrchestratorService(
    private val transcriberClient: TranscriberClient,
    private val summarizer: SummarizerPort,
) {
    private val executor = Executors.newCachedThreadPool()

    fun startJob(job: GatewayJob, params: TranscribeParams, summaryMode: SummaryMode, fileBytes: ByteArray) {
        executor.submit {
            runJob(job, params, summaryMode, fileBytes)
        }
    }

    private fun runJob(job: GatewayJob, params: TranscribeParams, summaryMode: SummaryMode, fileBytes: ByteArray) {
        job.startedAt = System.currentTimeMillis()
        job.status = GatewayJobStatus.PROCESSING

        try {
            // Phase 1: submit to transcriber
            val transcriberJobId = transcriberClient.submit(fileBytes, params.originalFileName, params)

            // Phase 2: poll transcriber
            val transcriptResult = pollTranscriber(job, transcriberJobId)

            // Phase 3: summarize
            val summary = if (summaryMode != SummaryMode.OFF && summarizer.isAvailable()) {
                job.status = GatewayJobStatus.SUMMARIZING
                job.progress = ProgressInfo(phase = "summarizing")
                summarizer.summarize(
                    text = transcriptResult.formattedText,
                    audioDurationSeconds = transcriptResult.audioDurationS,
                    mode = summaryMode,
                    language = params.language,
                )
            } else null

            job.result = transcriptResult.copy(summary = summary)
            job.status = GatewayJobStatus.DONE
        } catch (e: Exception) {
            job.status = GatewayJobStatus.FAILED
            job.error = e.message
        } finally {
            job.finishedAt = System.currentTimeMillis()
            job.durationS = (job.finishedAt!! - job.startedAt!!) / 1000.0
        }
    }

    private fun pollTranscriber(job: GatewayJob, transcriberJobId: String): JobResult {
        val deadlineMs = System.currentTimeMillis() + 4 * 60 * 60 * 1000L

        while (System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(5_000)
            val snapshot = transcriberClient.getJob(transcriberJobId)

            // Forward elapsed time
            snapshot.elapsedS?.let { job.elapsedS = it }

            when (snapshot.status) {
                "processing" -> snapshot.progress?.let { job.progress = it }
                "done" -> {
                    val segments = snapshot.segments ?: emptyList()
                    return JobResult(
                        segments = segments,
                        formattedText = formatSegments(segments),
                        audioDurationS = snapshot.audioDurationS,
                    )
                }
                "failed" -> throw RuntimeException(snapshot.error ?: "Transcriber job failed")
            }
        }
        throw RuntimeException("Timeout: transcription did not complete within 4 hours")
    }

    private fun formatTimestamp(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }

    private fun formatSegments(segments: List<Segment>): String =
        segments.joinToString("\n") { seg ->
            val ts = "[${formatTimestamp(seg.start)} --> ${formatTimestamp(seg.end)}]"
            if (seg.speaker != null) "$ts [${seg.speaker}] ${seg.text}" else "$ts ${seg.text}"
        }
}
