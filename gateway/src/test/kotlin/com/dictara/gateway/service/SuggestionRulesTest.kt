package com.dictara.gateway.service

import com.dictara.gateway.entity.SpeakerEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SuggestionRulesTest {

    private fun lib(vararg names: String) = names.map { SpeakerEntity(id = UUID.randomUUID(), userId = UUID.randomUUID(), name = it) }
    private fun match(name: String, library: List<SpeakerEntity>) = SuggestionService.sameFirstName(name, library)?.name

    // ── Tags that say they are never used together with others ─────────────────

    @Test
    fun `the tags a description says it is never used together with are read from it`() {
        assertThat(SuggestionService.excludedBy("A project. NEVER together with localisation_daily: a daily mentions it.")).containsExactly("localisation_daily")
        assertThat(SuggestionService.excludedBy("Any recording about my job. Never together with english_lesson, job_interview and Health.")).containsExactly("english_lesson", "job_interview", "health")
        assertThat(SuggestionService.excludedBy("never together with a-b_c")).containsExactly("a-b_c")
    }

    @Test
    fun `a description without it, or no description, excludes nothing`() {
        assertThat(SuggestionService.excludedBy("A meeting with the vendor. Not for dailies.")).isEmpty()
        assertThat(SuggestionService.excludedBy("")).isEmpty()
        assertThat(SuggestionService.excludedBy(null)).isEmpty()
    }

    // ── A proposed name that is a part of a library name, or the other way round ──

    @Test
    fun `the first name alone is the library speaker with the full name, and the other way round`() {
        assertThat(match("Patricia", lib("Patricia Brazao", "Fernando Pastor Durantez"))).isEqualTo("Patricia Brazao")
        assertThat(match("Chris Lloyd", lib("Chris", "Gabriel Catice"))).isEqualTo("Chris")
        assertThat(match("Максим", lib("Максим Пяткин"))).isEqualTo("Максим Пяткин")
    }

    @Test
    fun `a spelling that differs by one letter is the same first name, a different name is not`() {
        assertThat(match("Frederic", lib("Frederick"))).isEqualTo("Frederick")
        assertThat(match("Mohamed", lib("Mohammed Reza Farahani"))).isEqualTo("Mohammed Reza Farahani")
        assertThat(match("Anna", lib("Hanna", "Joan"))).isNull() // short names must agree exactly
        assertThat(match("Gabriel", lib("Gabriela Catice"))).isEqualTo("Gabriela Catice") // one letter more, long enough
        assertThat(match("Marcin", lib("Marcel Krawczyk"))).isNull()
    }

    @Test
    fun `two speakers that fit make a guess impossible, and two full names are never merged`() {
        assertThat(match("Alex", lib("Alex Liciecki", "Alex Smith"))).isNull()
        assertThat(match("Alex Smith", lib("Alex Liciecki"))).isNull() // both have a surname: another person
        assertThat(match("Anna", lib())).isNull()
        assertThat(match("  ", lib("Anna"))).isNull()
    }

    @Test
    fun `accents and case do not matter`() {
        assertThat(match("RENÉ", lib("René Lambert"))).isEqualTo("René Lambert")
        assertThat(match("rene", lib("René Lambert"))).isEqualTo("René Lambert")
    }
}
