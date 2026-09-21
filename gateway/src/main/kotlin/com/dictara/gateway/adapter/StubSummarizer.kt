package com.dictara.gateway.adapter

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.Analysis
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.SpeakerGuess
import com.dictara.gateway.port.SummarizerPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The summarizer of the `local` profile: no Gemini, no key. It answers after a short pause (so the
 * `summarizing` state can be seen) with a Markdown summary in the shape of the real one for the mode asked,
 * built from the first lines of the transcript, and with proposals worked out by simple rules (below), so
 * that the suggestions of the client can be tried. Nothing here is analysis in any real sense.
 *
 *  - title: the first sentence of the transcript;
 *  - tag: a tag of the library whose name is a word of the transcript, else the first tag of the library;
 *  - speaker: whoever introduces themselves ("I'm Anna", "my name is …", "Меня зовут …") is that voice.
 */
@Component
@Profile("local")
class StubSummarizer(private val props: DictaraProperties) : SummarizerPort {

    private val introduction = Regex("(?:my name is|this is|i'm|i am|меня зовут)\\s+(\\p{Lu}\\p{L}+)", RegexOption.IGNORE_CASE)

    override fun isAvailable() = true

    override fun analyze(input: AnalysisInput): Analysis {
        Thread.sleep((props.local.stub.summarizeSeconds * 1000).toLong())
        if (input.mode == SummaryMode.OFF) return Analysis(summary = "")

        // A line of the transcript is "[start --> end] [SPEAKER_00] text" or "[start --> end] text".
        val rows = input.text.lines().map { line ->
            val voice = Regex("\\[(SPEAKER_\\d+)]").find(line)?.groupValues?.get(1)
            voice to line.replace(Regex("^(\\[[^\\]]*]\\s*)+"), "").trim()
        }.filter { it.second.isNotEmpty() }
        val lines = rows.map { it.second }

        val title = lines.firstOrNull()?.split(Regex("(?<=[.!?…])\\s"))?.firstOrNull()?.trimEnd('.', '!', '?', '…')?.take(60)?.trim()
        val words = input.text.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).toSet()
        val tag = input.tags.firstOrNull { it.name.lowercase() in words } ?: input.tags.firstOrNull()
        val speakers = rows.mapNotNull { (voice, text) ->
            val found = introduction.find(text) ?: return@mapNotNull null
            voice?.let { SpeakerGuess(it, found.groupValues[1], found.value) }
        }.distinctBy { it.voice }

        return Analysis(
            summary = summary(lines, input.mode),
            title = title,
            tags = listOfNotNull(tag?.name),
            speakers = speakers,
        )
    }

    private fun summary(lines: List<String>, mode: SummaryMode): String {
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
