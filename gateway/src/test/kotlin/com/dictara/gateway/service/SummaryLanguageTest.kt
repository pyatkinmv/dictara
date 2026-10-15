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
}
