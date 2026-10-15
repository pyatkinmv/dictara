package com.dictara.gateway.service

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.job.JobTracker
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.repository.BacklogQueries
import com.dictara.gateway.repository.SummaryRegenerationQueries
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The one-off run that asks Gemini again about transcriptions made before the analysis was as good as it is now
 * (`POST /admin/reanalyze`, see AdminController): the summary is replaced and what the user has not settled (title, tags,
 * speakers) is proposed again, exactly as in the user's own "Regenerate" ([SummaryRegenerationService.analyzeAndReplace]) —
 * but it is not that user's regeneration: it is not counted against the plan, and the button stays as it was.
 *
 * Made to be driven carefully, because every call costs money: the caller names the ids (a dry run says what a batch
 * would cost without asking Gemini), one batch runs at a time, one transcription after another with a pause, a
 * transcription already done by this run is skipped unless asked again, a batch stops after a few failures in a row,
 * and it can be stopped.
 */
@Service
class BacklogReanalysisService(
    private val queries: BacklogQueries,
    private val regenerationQueries: SummaryRegenerationQueries,
    private val regeneration: SummaryRegenerationService,
    private val summarizer: SummarizerPort,
    private val jobTracker: JobTracker,
    private val props: DictaraProperties,
) {
    /** What a batch would do. [skipped] says why each id left out was left out (reason to ids). */
    data class Plan(
        val eligible: List<UUID>,
        val skipped: Map<String, List<String>>,
        val estimatedInputTokens: Long,
        val estimatedCostUsd: Double,
    )

    /** How far the current (or last) batch is. */
    data class Status(
        val batch: String,
        val state: String,
        val total: Int,
        val done: Int,
        val failed: Int,
        val skippedWhileRunning: Int,
        val current: String?,
        val tokensIn: Long,
        val tokensOut: Long,
        val tokensThinking: Long,
        val costUsd: Double,
        val startedAt: String,
        val finishedAt: String?,
        val lastErrors: List<String>,
    )

    private class Batch(val id: String, val total: Int) {
        val startedAt: Instant = Instant.now()
        @Volatile var finishedAt: Instant? = null
        @Volatile var state = "running" // running, finished, stopped, stopped_after_failures
        @Volatile var stopRequested = false
        @Volatile var current: UUID? = null
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val skippedWhileRunning = AtomicInteger()
        val tokensIn = AtomicLong()
        val tokensOut = AtomicLong()
        val tokensThinking = AtomicLong()
        val errors = CopyOnWriteArrayList<String>()
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "backlog-reanalysis").apply { isDaemon = true } }

    @Volatile private var batch: Batch? = null

    /** What would happen to [ids] of [userId]: which are eligible, which are left out and why, and about what it would cost. */
    fun plan(userId: UUID, ids: List<String>, force: Boolean): Plan {
        val parsed = ids.map { raw -> raw to runCatching { UUID.fromString(raw.trim()) }.getOrNull() }
        val rows = queries.rows(parsed.mapNotNull { it.second }.toSet())
        val skipped = linkedMapOf<String, MutableList<String>>()
        fun skip(reason: String, id: String) = skipped.getOrPut(reason) { mutableListOf() }.add(id)
        val eligible = mutableListOf<UUID>()
        var chars = 0L
        var phrases = 0L
        for ((raw, id) in parsed) {
            val row = id?.let { rows[it] }
            when {
                id == null -> skip("invalid", raw)
                row == null -> skip("not_found", raw)
                row.userId != userId -> skip("not_owner", raw)
                row.status != "done" || row.phrases == 0 -> skip("not_ready", raw)
                row.backfilledAt != null && !force -> skip("already_done", raw)
                id in eligible -> skip("duplicate", raw)
                else -> { eligible += id; chars += row.chars; phrases += row.phrases }
            }
        }
        // A rough guess: about 3 characters a token for the text, a dozen tokens of timestamp and label per phrase, ~900 of
        // instructions and library; and on the recordings tried the answer and the "thinking" came to about 5,000 tokens.
        val inputTokens = chars / 3 + phrases * 12 + eligible.size * 900L
        val cost = cost(inputTokens, eligible.size * 5_000L)
        return Plan(eligible, skipped, inputTokens, cost)
    }

    /** Starts the batch of the eligible ids of [ids] in the background; 409 when one is already running or Gemini is not set up. */
    fun start(userId: UUID, ids: List<String>, force: Boolean): Plan {
        if (!summarizer.isAvailable()) throw ResponseStatusException(HttpStatus.CONFLICT, "The summary service is not available")
        synchronized(this) {
            if (batch?.state == "running") throw ResponseStatusException(HttpStatus.CONFLICT, "A batch is already running; stop it or wait for it")
            val plan = plan(userId, ids, force)
            if (plan.eligible.isEmpty()) throw ResponseStatusException(HttpStatus.CONFLICT, "Nothing to do: no id is eligible (see skipped)")
            val started = Batch(UUID.randomUUID().toString().take(8), plan.eligible.size)
            batch = started
            worker.submit { run(started, plan.eligible) }
            return plan
        }
    }

    fun status(): Status? = batch?.let { b ->
        Status(
            batch = b.id, state = b.state, total = b.total, done = b.done.get(), failed = b.failed.get(),
            skippedWhileRunning = b.skippedWhileRunning.get(), current = b.current?.toString(),
            tokensIn = b.tokensIn.get(), tokensOut = b.tokensOut.get(), tokensThinking = b.tokensThinking.get(),
            costUsd = cost(b.tokensIn.get(), b.tokensOut.get() + b.tokensThinking.get()),
            startedAt = b.startedAt.toString(), finishedAt = b.finishedAt?.toString(), lastErrors = b.errors.takeLast(5),
        )
    }

    /** Asks the running batch to stop after the transcription it is on; false when none is running. */
    fun stop(): Boolean {
        val b = batch?.takeIf { it.state == "running" } ?: return false
        b.stopRequested = true
        return true
    }

    private fun run(b: Batch, ids: List<UUID>) {
        log.info("Backlog reanalysis {} started: {} transcriptions", b.id, ids.size)
        var inARow = 0
        try {
            jobTracker.tracked("reanalyze_backlog") {
                for ((n, id) in ids.withIndex()) {
                    if (b.stopRequested) { b.state = "stopped"; break }
                    // A transcription the user is regenerating at this very moment is left for next time.
                    if (!regenerationQueries.claim(id, null)) { b.skippedWhileRunning.incrementAndGet(); continue }
                    b.current = id
                    try {
                        val analysis = regeneration.analyzeAndReplace(id)
                        regenerationQueries.finishBackfill(id)
                        b.done.incrementAndGet()
                        analysis.usage?.let { b.tokensIn.addAndGet(it.prompt.toLong()); b.tokensOut.addAndGet(it.output.toLong()); b.tokensThinking.addAndGet(it.thoughts.toLong()) }
                        inARow = 0
                        log.info("Backlog reanalysis {}: {}/{} done ({})", b.id, n + 1, ids.size, id)
                    } catch (e: Exception) {
                        regenerationQueries.release(id)
                        b.failed.incrementAndGet()
                        b.errors += "$id: ${e.message?.take(160)}"
                        log.warn("Backlog reanalysis {}: {} failed: {}", b.id, id, e.message)
                        if (++inARow >= props.admin.stopAfterFailures) { b.state = "stopped_after_failures"; break }
                    } finally {
                        b.current = null
                    }
                    if (props.admin.reanalyzePauseMs > 0) Thread.sleep(props.admin.reanalyzePauseMs)
                }
                b.done.get()
            }
        } catch (e: Exception) {
            log.error("Backlog reanalysis {} ended with an error: {}", b.id, e.message)
            b.errors += "batch: ${e.message?.take(160)}"
            if (b.state == "running") b.state = "stopped_after_failures"
        } finally {
            if (b.state == "running") b.state = "finished"
            b.finishedAt = Instant.now()
            log.info("Backlog reanalysis {} {}: {} done, {} failed of {}", b.id, b.state, b.done.get(), b.failed.get(), b.total)
        }
    }

    private companion object {
        /** `gemini-2.5-flash`, dollars per million tokens: input, and output (the "thinking" is billed as output). */
        const val INPUT_USD_PER_MILLION = 0.30
        const val OUTPUT_USD_PER_MILLION = 2.50

        fun cost(inputTokens: Long, outputTokens: Long): Double =
            Math.round((inputTokens * INPUT_USD_PER_MILLION + outputTokens * OUTPUT_USD_PER_MILLION) / 1_000_000.0 * 10_000) / 10_000.0
    }
}
