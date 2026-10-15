package com.dictara.gateway.controller

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.service.BacklogReanalysisService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

/**
 * The operator's endpoints. They are for one person with the key (`ADMIN_KEY`, header `X-Admin-Key`), called on the VM
 * at `localhost:8080`; nginx does not pass `/api/admin/` on. With no key configured they answer 404, as if they did not exist.
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val props: DictaraProperties,
    private val backlog: BacklogReanalysisService,
) {
    /** `user_id`: whose transcriptions (every id has to be theirs); `ids`; `dry_run`: only say what would happen and what it costs; `force`: also those the backlog run already did. */
    data class ReanalyzeRequest(val userId: String?, val ids: List<String>?, val dryRun: Boolean = false, val force: Boolean = false)

    data class Reanalyzed(val started: Boolean, val plan: BacklogReanalysisService.Plan)

    /**
     * Asks Gemini again about the given transcriptions, one after another, in the background; see [BacklogReanalysisService].
     * With `dry_run` nothing is asked: the answer says which ids would be done and about what it would cost (200).
     * Otherwise 202 with the same, 409 when a batch is already running or no id is eligible. More ids than
     * `dictara.admin.max-ids-per-call` is a 400.
     */
    @PostMapping("/reanalyze")
    fun reanalyze(@RequestHeader("X-Admin-Key", required = false) key: String?, @RequestBody body: ReanalyzeRequest): org.springframework.http.ResponseEntity<Reanalyzed> {
        requireKey(key)
        val userId = body.userId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required: whose transcriptions these are")
        val ids = body.ids.orEmpty()
        if (ids.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ids is empty")
        if (ids.size > props.admin.maxIdsPerCall) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At most ${props.admin.maxIdsPerCall} ids per call")
        }
        if (body.dryRun) return org.springframework.http.ResponseEntity.ok(Reanalyzed(false, backlog.plan(userId, ids, body.force)))
        return org.springframework.http.ResponseEntity.accepted().body(Reanalyzed(true, backlog.start(userId, ids, body.force)))
    }

    /** How far the current (or last) batch is, what it has used and what that cost so far; 404 before the first one. */
    @GetMapping("/reanalyze/status")
    fun status(@RequestHeader("X-Admin-Key", required = false) key: String?): BacklogReanalysisService.Status {
        requireKey(key)
        return backlog.status() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No batch has been started")
    }

    /** Stops the running batch after the transcription it is on. */
    @PostMapping("/reanalyze/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun stop(@RequestHeader("X-Admin-Key", required = false) key: String?): Map<String, Boolean> {
        requireKey(key)
        return mapOf("stopping" to backlog.stop())
    }

    private fun requireKey(given: String?) {
        val expected = props.admin.key
        if (expected.isBlank()) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
        // Compared in constant time, and a wrong key says nothing about the right one.
        val ok = given != null && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
        if (!ok) throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong or missing admin key")
    }
}
