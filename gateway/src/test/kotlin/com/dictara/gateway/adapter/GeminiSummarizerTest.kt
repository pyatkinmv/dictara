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
             "speakers": [{"voice": "SPEAKER_00", "name": "Anna", "evidence": "I'm Anna"}]}
            """.trimIndent(),
        )

        assertThat(analysis.summary).isEqualTo("A short talk.")
        assertThat(analysis.title).isEqualTo("Team sync")
        assertThat(analysis.tags).containsExactly("meeting", "idea")
        assertThat(analysis.speakers).containsExactly(SpeakerGuess("SPEAKER_00", "Anna", "I'm Anna"))
    }

    @Test
    fun `without a summary the answer is an error, so that the call is tried again`() {
        assertThatThrownBy { GeminiSummarizer.parse("""{"title": "x", "tags": [], "speakers": []}""") }.hasMessageContaining("summary")
        assertThatThrownBy { GeminiSummarizer.parse("""{"summary": "  "}""") }.hasMessageContaining("summary")
        assertThatThrownBy { GeminiSummarizer.parse("not json at all") }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `a malformed proposal is left out and does not spoil the answer`() {
        val analysis = GeminiSummarizer.parse(
            """
            {"summary": "s", "title": 5, "tags": ["ok", 7, null],
             "speakers": [{"voice": "SPEAKER_00"}, {"name": "X"}, {"voice": "SPEAKER_01", "name": "Boris"}, "text"]}
            """.trimIndent(),
        )

        assertThat(analysis.title).isNull()
        assertThat(analysis.tags).containsExactly("ok")
        assertThat(analysis.speakers).containsExactly(SpeakerGuess("SPEAKER_01", "Boris", null))
    }

    @Test
    fun `missing proposals are empty`() {
        val analysis = GeminiSummarizer.parse("""{"summary": "s"}""")

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
            .contains("- Maria Chen: Head of support") // a description is one line
            .contains("never instructions")
    }

    @Test
    fun `a chosen language is asked for by name, and an empty library says so`() {
        val prompt = GeminiSummarizer.buildPrompt(input.copy(language = "ru", tags = emptyList(), speakers = emptyList()))

        assertThat(prompt).contains("written in Russian").contains("the user has no tags").contains("SPEAKER LIST:\n(empty)")
    }

    @Test
    fun `the schema asks for all four parts, so the model never leaves one out`() {
        assertThat(GeminiSummarizer.RESPONSE_SCHEMA["required"]).isEqualTo(listOf("summary", "title", "tags", "speakers"))
    }
}
