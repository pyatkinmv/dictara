package com.dictara.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dictara")
data class DictaraProperties(
    val transcriber: TranscriberProps = TranscriberProps(),
    val summarizer: SummarizerProps = SummarizerProps(),
    val storage: StorageProps = StorageProps(),
) {
    data class TranscriberProps(
        val url: String = "http://localhost:8000",
        val pollIntervalMs: Long = 5000,
        val timeoutHours: Long = 4,
    )

    data class SummarizerProps(
        val provider: String = "gemini",
        val gemini: GeminiProps = GeminiProps(),
    )

    data class GeminiProps(
        val apiKey: String = "",
        val model: String = "gemini-2.5-flash",
    )

    data class StorageProps(
        val gcs: GcsProps = GcsProps(),
    )

    /** Empty bucket = GCS audio storage disabled — gateway falls back to storing
     *  audio as a BLOB in Postgres and streaming it to the transcriber over HTTP
     *  (see AudioStorageClient for why this matters on Cloud Run). */
    data class GcsProps(
        val bucket: String = "",
    )
}
