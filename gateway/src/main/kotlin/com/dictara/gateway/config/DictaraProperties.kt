package com.dictara.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dictara")
data class DictaraProperties(
    val transcriber: TranscriberProps = TranscriberProps(),
    val summarizer: SummarizerProps = SummarizerProps(),
    val storage: StorageProps = StorageProps(),
    val local: LocalProps = LocalProps(),
    val admin: AdminProps = AdminProps(),
    val recordedAt: RecordedAtProps = RecordedAtProps(),
) {
    /**
     * The operator's endpoints (`/admin/...`, see AdminController). They are off while [key] is blank. They are not
     * reachable from outside (nginx answers 404 for `/api/admin/`): they are called on the VM, on localhost:8080.
     */
    data class AdminProps(
        val key: String = "",
        /** How many ids one call to `/admin/reanalyze` may carry. */
        val maxIdsPerCall: Int = 300,
        /** Pause between two transcriptions of a batch, so that Gemini is not hammered. */
        val reanalyzePauseMs: Long = 1500,
        /** A batch stops after this many failures in a row (Gemini down, a bad key, quota used up). */
        val stopAfterFailures: Int = 5,
    )

    data class TranscriberProps(
        val url: String = "http://localhost:8000",
        val pollIntervalMs: Long = 5000,
        val timeoutHours: Long = 4,
    )

    data class SummarizerProps(
        val provider: String = "gemini",
        val gemini: GeminiProps = GeminiProps(),
        /** Pause before a failed analysis is asked for again (three tries in all). */
        val retryPauseMs: Long = 2000,
    )

    data class GeminiProps(
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash",
    )

    data class RecordedAtProps(
        /** The zone in which a time written in a file name (`20260608_213900.m4a`) is read; the container's own
         *  creation_time is UTC and does not need it. Only a fallback, so a rough value is enough. */
        val nameZone: String = "Asia/Dubai",
    )

    data class StorageProps(
        val gcs: GcsProps = GcsProps(),
        /** How many files may be on their way to storage at once. Every upload holds a buffer in memory; the 2 GB
         *  VM could not take dozens of them when a user forwarded a whole chat of recordings to the bot. The rest wait. */
        val maxConcurrentUploads: Int = 4,
    )

    data class GcsProps(
        val bucket: String = "",
    )

    /** Settings that only matter under the `local` profile. */
    data class LocalProps(
        val stub: StubProps = StubProps(),
    )

    /** How long the stub transcriber keeps a job in each state. */
    data class StubProps(
        val pendingSeconds: Double = 2.0,
        val transcribeSeconds: Double = 10.0,
        val diarizeSeconds: Double = 5.0,
        /** How long the stub summarizer takes, so that the summarizing state can be seen. */
        val summarizeSeconds: Double = 3.0,
        /** How long the local Telegram bot stub waits before "tapping Confirm" itself, so the "Check your
         *  Telegram" screen is visible. */
        val loginConfirmSeconds: Double = 2.0,
    )
}
