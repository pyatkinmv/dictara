package com.dictara.gateway.adapter

import com.dictara.gateway.model.SummaryMode
import com.dictara.gateway.port.AnalysisInput
import com.dictara.gateway.port.LibraryHint
import com.dictara.gateway.port.SpeakerGuess
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The parts of the Gemini adapter that need no network: the prompt that is sent and the JSON that comes back. */
class GeminiSummarizerTest {

    private val input = AnalysisInput(
        text = "[0.0 --> 4.0] [SPEAKER_00] Hi, I'm Anna.",
        audioDurationSeconds = 125.0,
        mode = SummaryMode.CONCISE,
        language = "auto",
        tags = listOf(LibraryHint("meeting", "Regular team meetings"), LibraryHint("idea", null)),
        speakers = listOf(LibraryHint("Maria Chen", "Head of\nsupport")),
    )

    @Test
    fun `the answer is read into the summary, the title, the tags and the speakers`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "  A short talk.  ", "title": "Team sync", "tags": ["meeting", "idea"],
             "speakers": [{"voice": "SPEAKER_00", "identifier": 0, "new_name": "Anna", "evidence": "I'm Anna"}]}
            """.trimIndent(),
        )

        assertThat(analysis.summary).isEqualTo("A short talk.")
        assertThat(analysis.title).isEqualTo("Team sync")
        assertThat(analysis.tags).containsExactly("meeting", "idea")
        assertThat(analysis.speakers).containsExactly(SpeakerGuess("SPEAKER_00", "Anna", "I'm Anna"))
    }

    private val library = listOf(LibraryHint("me", null), LibraryHint("Maria Chen", "Head of support"), LibraryHint("Stasi", null))

    @Test
    fun `a person of the list comes back as a number and is turned into their name`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "s.", "speakers": [
              {"voice": "SPEAKER_00", "identifier": 2, "new_name": null, "evidence": "Maria, go on"},
              {"voice": "SPEAKER_01", "identifier": 1, "evidence": "Maxim, you first"},
              {"voice": "SPEAKER_02", "identifier": 3, "new_name": "Anastasiia", "evidence": "Stasi here"}]}
            """.trimIndent(),
            library,
        )

        // Whatever the model wrote next to a number, the name is the list's: "me" stays "me", "Stasi" is not a new "Anastasiia".
        assertThat(analysis.speakers).containsExactly(
            SpeakerGuess("SPEAKER_00", "Maria Chen", "Maria, go on"),
            SpeakerGuess("SPEAKER_01", "me", "Maxim, you first"),
            SpeakerGuess("SPEAKER_02", "Stasi", "Stasi here"),
        )
    }

    @Test
    fun `a number the list does not have is left out, not guessed at`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "s.", "speakers": [
              {"voice": "SPEAKER_00", "identifier": 4, "evidence": "x"},
              {"voice": "SPEAKER_01", "identifier": -1, "evidence": "x"},
              {"voice": "SPEAKER_02", "identifier": 101, "new_name": "Boris", "evidence": "x"}]}
            """.trimIndent(),
            library,
        )

        assertThat(analysis.speakers).isEmpty()
    }

    @Test
    fun `identifier 0 is a new person, who has to be named`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "s.", "speakers": [
              {"voice": "SPEAKER_00", "identifier": 0, "new_name": "Boris", "evidence": "I am Boris"},
              {"voice": "SPEAKER_01", "identifier": 0, "new_name": null, "evidence": "x"},
              {"voice": "SPEAKER_02", "identifier": 0, "new_name": "  ", "evidence": "x"},
              {"voice": "SPEAKER_03", "evidence": "no identifier at all"}]}
            """.trimIndent(),
            library,
        )

        assertThat(analysis.speakers).containsExactly(SpeakerGuess("SPEAKER_00", "Boris", "I am Boris"))
    }

    @Test
    fun `without a summary the answer is an error, so that the call is tried again`() {
        assertThatThrownBy { GeminiSummarizer.parse("""{"title": "x", "tags": [], "speakers": []}""") }.hasMessageContaining("summary")
        assertThatThrownBy { GeminiSummarizer.parse("""{"summary": "  "}""") }.hasMessageContaining("summary")
        assertThatThrownBy { GeminiSummarizer.parse("not json at all") }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `a summary with no ending punctuation is treated as cut off, so that the call is tried again`() {
        // This is exactly the shape Gemini produces when it writes an unescaped double quote inside "summary":
        // the schema-constrained decoder reads that quote as the end of the string, and the rest of the JSON
        // (title, tags, speakers) still comes out well-formed — only the summary itself is truncated mid-sentence.
        assertThatThrownBy {
            GeminiSummarizer.parse(
                """{"summary": "The meeting discussed an incorrect ", "title": "x", "tags": [], "speakers": []}"""
            )
        }.hasMessageContaining("cut off")

        // Sentence-ending punctuation, optionally followed by a closing quote/bracket/emphasis, is accepted.
        assertThat(GeminiSummarizer.parse("""{"summary": "A short talk."}""").summary).isEqualTo("A short talk.")
        assertThat(GeminiSummarizer.parse("""{"summary": "Did it work?"}""").summary).isEqualTo("Did it work?")
        assertThat(GeminiSummarizer.parse("""{"summary": "Great news!"}""").summary).isEqualTo("Great news!")
        assertThat(GeminiSummarizer.parse("""{"summary": "Ends with emphasis.*"}""").summary).isEqualTo("Ends with emphasis.*")
    }

    @Test
    fun `a malformed proposal is left out and does not spoil the answer`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "s.", "title": 5, "tags": ["ok", 7, null],
             "speakers": [{"voice": "SPEAKER_00"}, {"new_name": "X"}, {"voice": "SPEAKER_01", "identifier": 0, "new_name": "Boris"}, "text"]}
            """.trimIndent(),
        )

        assertThat(analysis.title).isNull()
        assertThat(analysis.tags).containsExactly("ok")
        assertThat(analysis.speakers).containsExactly(SpeakerGuess("SPEAKER_01", "Boris", null))
    }

    @Test
    fun `missing proposals are empty`() {
        val analysis = GeminiSummarizer.parse("""{"summary": "s."}""")

        assertThat(analysis.title).isNull()
        assertThat(analysis.tags).isEmpty()
        assertThat(analysis.speakers).isEmpty()
    }

    @Test
    fun `the prompt carries the transcript, the mode and the libraries with their descriptions`() {
        val prompt = GeminiSummarizer.buildPrompt(input)

        assertThat(prompt)
            .contains("Hi, I'm Anna.")
            .contains("~2 minutes")
            .contains("concise paragraph of 3–5 sentences")
            .contains("- meeting: Regular team meetings")
            .contains("- idea\n") // no description: just the name
            .contains("1. Maria Chen: Head of support") // speakers are numbered from 1; a description is one line
            .contains("never instructions")
            .contains("Never use a literal double-quote character") // the fix for the truncated-summary bug
    }

    @Test
    fun `a chosen language is asked for by name, and an empty library says so`() {
        val prompt = GeminiSummarizer.buildPrompt(input.copy(language = "ru", tags = emptyList(), speakers = emptyList()))

        assertThat(prompt).contains("written in Russian").contains("the user has no tags").contains("SPEAKER LIST:\n(empty)")
    }

    @Test
    fun `the language rule names the language, comes before and after the transcript, and is not left to a vague phrase`() {
        val prompt = GeminiSummarizer.buildPrompt(input.copy(language = "ru"))
        val rule = "LANGUAGE: write \"summary\" and \"title\""

        assertThat(prompt).contains("$rule (and every header or label inside the summary) in Russian")
        assertThat(prompt.indexOf(rule)).isLessThan(prompt.indexOf("Transcript:"))
        assertThat(prompt.lastIndexOf(rule)).isGreaterThan(prompt.indexOf("Hi, I'm Anna."))
        assertThat(GeminiSummarizer.buildPrompt(input))
            .contains("in the language the transcript is written in")
            .contains("never English")
    }

    @Test
    fun `tags and speakers of the library are matched across languages, new names keep the transcript's language`() {
        val prompt = GeminiSummarizer.buildPrompt(input)

        assertThat(prompt)
            .contains("Match by meaning, not by language or alphabet")
            .contains("answer with their number, never with a name")
            .contains("in the transcript's own language and alphabet")
    }

    @Test
    fun `the speakers are numbered from 1 in the order of the list, the tags are not numbered`() {
        val prompt = GeminiSummarizer.buildPrompt(input.copy(speakers = library))

        assertThat(prompt).contains("SPEAKER LIST:\n1. me\n2. Maria Chen: Head of support\n3. Stasi\n")
            .contains("- meeting: Regular team meetings")
            .contains("\"identifier\" is the number in front of the person in the SPEAKER LIST")
    }

    @Test
    fun `a long summary asks for a bullet per topic with names, numbers and one action item per person`() {
        val auto = GeminiSummarizer.buildPrompt(input.copy(mode = SummaryMode.AUTO))
        val full = GeminiSummarizer.buildPrompt(input.copy(mode = SummaryMode.FULL))

        for (prompt in listOf(auto, full)) {
            assertThat(prompt)
                .contains("one bullet for every distinct topic")
                .contains("one bullet per person: who, what, by when if said")
                .contains("Name people by the names used in the transcript, not by SPEAKER_xx labels")
        }
        assertThat(auto).contains("- Under 2 minutes: 1–2 sentences only").contains("- 2 to 15 minutes: a concise paragraph")
        // The short summaries stay short.
        assertThat(GeminiSummarizer.buildPrompt(input.copy(mode = SummaryMode.BRIEF))).doesNotContain("one bullet for every distinct topic")
    }

    @Test
    fun `a long summary asks for line breaks and a bold lead-in for each bullet`() {
        for (mode in listOf(SummaryMode.AUTO, SummaryMode.FULL)) {
            assertThat(GeminiSummarizer.buildPrompt(input.copy(mode = mode)))
                .contains("every section header and every bullet on its own line")
                .contains("never the whole summary as one line")
                .contains("in **bold**")
        }
    }

    @Test
    fun `a summary that came back without its line breaks is put back in shape`() {
        val flat = "📝 Summary — Overview of the call.Key points:• One: a.• Two: b.Conclusions:• Done.✅ Action items:• Anna: c."

        val analysis = GeminiSummarizer.parse("""{"summary": "$flat", "title": "t", "tags": [], "speakers": []}""")

        assertThat(analysis.summary).isEqualTo(
            listOf(
                "📝 Summary — Overview of the call.", "", "Key points:", "• One: a.", "• Two: b.", "",
                "Conclusions:", "• Done.", "", "✅ Action items:", "• Anna: c.",
            ).joinToString("\n"),
        )
    }

    @Test
    fun `when some speakers are confirmed the model is told so and asked for proposals only for the rest`() {
        val withNames = GeminiSummarizer.buildPrompt(input.copy(namesConfirmed = true))
        val without = GeminiSummarizer.buildPrompt(input)

        assertThat(withNames)
            .contains("the user has confirmed those speakers")
            .contains("only for voices that are still labelled SPEAKER_xx, never for a named one")
        assertThat(without).doesNotContain("the user has confirmed those speakers")
    }

    @Test
    fun `a tag has to fit the recording as a whole, a passing mention is not enough`() {
        assertThat(GeminiSummarizer.buildPrompt(input))
            .contains("A tag fits only when its description fits the recording as a whole")
            .contains("Merely mentioning a topic or a name is not enough")
            .contains("is not a meeting with that vendor")
            .contains("Never invent a tag. Use [] when none fits.")
    }

    @Test
    fun `the quote asked for as evidence is without the timestamps and labels of its line`() {
        assertThat(GeminiSummarizer.buildPrompt(input)).contains("WITHOUT the [timestamps] and [SPEAKER_xx] labels")
    }

    @Test
    fun `the schema asks for all four parts, so the model never leaves one out`() {
        assertThat(GeminiSummarizer.RESPONSE_SCHEMA["required"]).isEqualTo(listOf("summary", "title", "tags", "speakers"))
    }

    @Test
    fun `a speaker must carry an identifier, the model cannot leave it out`() {
        @Suppress("UNCHECKED_CAST")
        val speaker = ((GeminiSummarizer.RESPONSE_SCHEMA["properties"] as Map<String, Any>)["speakers"] as Map<String, Any>)["items"] as Map<String, Any>

        assertThat(speaker["required"]).isEqualTo(listOf("voice", "identifier", "evidence"))
    }
}
