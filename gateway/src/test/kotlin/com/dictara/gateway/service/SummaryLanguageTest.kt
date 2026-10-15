package com.dictara.gateway.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SummaryLanguageTest {

    @Test
    fun `a language the user chose wins over what the transcriber found`() {
        assertThat(summaryLanguage("en", "ru")).isEqualTo("en")
    }

    @Test
    fun `on auto the language found in the audio is used`() {
        assertThat(summaryLanguage("auto", "ru")).isEqualTo("ru")
        assertThat(summaryLanguage("auto", " RU ")).isEqualTo("ru")
    }

    @Test
    fun `nothing usable from the transcriber leaves auto`() {
        assertThat(summaryLanguage("auto", null)).isEqualTo("auto")
        assertThat(summaryLanguage("auto", "")).isEqualTo("auto")
        assertThat(summaryLanguage("auto", "not a code")).isEqualTo("auto")
    }

    @Test
    fun `without a detected language mostly Cyrillic text is Russian, the markers of the transcript do not count`() {
        val ru = "[0:00:01 --> 0:00:04] [SPEAKER_00] Привет, меня зовут Анна, это встреча про бюджет.\n[0:00:04 --> 0:00:09] [SPEAKER_01] Спасибо."

        assertThat(summaryLanguage("auto", null, ru)).isEqualTo("ru")
        assertThat(summaryLanguage("auto", "", ru)).isEqualTo("ru")
    }

    @Test
    fun `Latin text, an empty text and a chosen language are not guessed`() {
        assertThat(summaryLanguage("auto", null, "[0:00:01 --> 0:00:04] [SPEAKER_00] Hello, I am Anna.")).isEqualTo("auto")
        assertThat(summaryLanguage("auto", null, "")).isEqualTo("auto")
        assertThat(summaryLanguage("de", null, "Привет")).isEqualTo("de")
        assertThat(summaryLanguage("auto", "en", "Привет")).isEqualTo("en") // what the transcriber found wins over the guess
    }
}
