package com.dictara.gateway.adapter

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.SummarizerPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The summarizer of the `local` profile: no Gemini, no key. It answers after a short pause (so the
 * `summarizing` state can be seen) with a Markdown summary in the shape of the real one for the mode asked,
 * built from the first lines of the transcript. Nothing here is a summary in any real sense.
 */
@Component
@Profile("local")
class StubSummarizer(private val props: DictaraProperties) : SummarizerPort {

    override fun isAvailable() = true

    override fun summarize(text: String, audioDurationSeconds: Double?, mode: SummaryMode, language: String): String {
        Thread.sleep((props.local.stub.summarizeSeconds * 1000).toLong())
        if (mode == SummaryMode.OFF) return ""
        // A line of the transcript is "[start --> end] [SPEAKER_00] text" or "[start --> end] text".
        val lines = text.lines().map { it.replace(Regex("^(\\[[^\\]]*\\]\\s*)+"), "").trim() }.filter { it.isNotEmpty() }
        val opening = lines.firstOrNull()?.take(120) ?: "The recording has no speech."
        val second = lines.getOrNull(1)?.take(120)
        return when (mode) {
            SummaryMode.OFF -> ""
            SummaryMode.BRIEF -> "A short recording that opens with: \"$opening\""
            SummaryMode.CONCISE ->
                "This is a stub summary (local profile, no Gemini). The recording starts with \"$opening\"" +
                    (second?.let { " and continues with \"$it\"" } ?: "") +
                    ". A real summary would cover the main topic, key points and conclusions."
            SummaryMode.AUTO, SummaryMode.FULL -> buildString {
                append("A stub summary of ${lines.size} spoken lines (local profile, no Gemini). It opens with \"$opening\".\n\n")
                append("**Key points**\n")
                append("* ${second ?: opening}\n")
                append("* ${lines.getOrNull(2)?.take(120) ?: "Nothing more was said."}\n\n")
                append("**Action items**\n")
                append("* Replace this stub with a real summary when Gemini is configured.")
            }
        }
    }
}
