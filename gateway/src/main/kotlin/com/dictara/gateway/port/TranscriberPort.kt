package com.dictara.gateway.port

import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.model.Segment

data class TranscribeParams(
    val model: String,
    val language: String,
    val diarize: Boolean,
    val numSpeakers: Int?,
    val originalFileName: String,
)

data class TranscriberJobSnapshot(
    val status: String,
    val progress: ProgressInfo?,
    val segments: List<Segment>?,
    val audioDurationS: Double?,
    val detectedLanguage: String?,
    val durationS: Double?,
    val elapsedS: Double?,
    val error: String?,
    val retryable: Boolean = true,
)

/** What the orchestrator needs from a transcriber: submit an audio file by reference and poll the job.
 *  [com.dictara.gateway.client.TranscriberClient] talks to the real service over HTTP; the `local`
 *  profile swaps in [com.dictara.gateway.adapter.StubTranscriber]. */
interface TranscriberPort {
    /** Starts a job for the audio at [storageUri] and returns the transcriber's job id. */
    fun submitByReference(storageUri: String, params: TranscribeParams): String

    /** Current state of a job: `pending`, `processing`, `done` or `failed`. */
    fun getJob(jobId: String): TranscriberJobSnapshot
}
