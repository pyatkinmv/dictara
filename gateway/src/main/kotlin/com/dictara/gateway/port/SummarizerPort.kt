package com.dictara.gateway.port

import com.dictara.gateway.model.SummaryMode

/** A library entry as the model is told about it: a name and what the user wrote about it. */
data class LibraryHint(val name: String, val description: String?)

/** What one call to the summarizer is given: the transcript, how to summarize it and what the user's libraries hold. */
data class AnalysisInput(
    val text: String,
    val audioDurationSeconds: Double?,
    val mode: SummaryMode,
    val language: String,
    /** The user's tags: the only ones the model may propose. */
    val tags: List<LibraryHint> = emptyList(),
    /** The user's speakers: proposed as they are; a new one only when the transcript itself gives the name. */
    val speakers: List<LibraryHint> = emptyList(),
)

/** "This voice is [name]", with the words of the transcript that show it. */
data class SpeakerGuess(val voice: String, val name: String, val evidence: String?)

/**
 * What comes back from the one call. Only [summary] is relied on; the rest are proposals that the gateway
 * checks against the transcript and the libraries before it keeps any of them.
 */
data class Analysis(
    /** Markdown, as the model wrote it. */
    val summary: String,
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val speakers: List<SpeakerGuess> = emptyList(),
)

interface SummarizerPort {
    fun isAvailable(): Boolean

    /** Summarizes the transcript and, in the same call, proposes a title, tags and speaker names. */
    fun analyze(input: AnalysisInput): Analysis
}
