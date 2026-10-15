package com.dictara.gateway.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SummaryFormatTest {

    /** The shape Gemini returned for a long meeting: no line breaks at all, sections glued to the sentence before. */
    private val flattened =
        "📝 Summary — This meeting covers project updates and blockers. The team discussed approvals." +
            "Key points:• Regulatory challenges: The initiative faces hurdles in gaining approvals. France was excluded." +
            "• Internal support: Patricia could not get database access. A lead suggested using 'open code'." +
            "• Maxim's updates: He reported issues with acknowledgements, with a 30-40% success rate." +
            "Conclusions:• The team is contending with blockers.• Prioritization is crucial." +
            "✅ Action items:• Patricia: Obtain designs today.• Maxim: Investigate the rejection rate."

    private val expected = listOf(
        "📝 Summary — This meeting covers project updates and blockers. The team discussed approvals.",
        "",
        "Key points:",
        "• Regulatory challenges: The initiative faces hurdles in gaining approvals. France was excluded.",
        "• Internal support: Patricia could not get database access. A lead suggested using 'open code'.",
        "• Maxim's updates: He reported issues with acknowledgements, with a 30-40% success rate.",
        "",
        "Conclusions:",
        "• The team is contending with blockers.",
        "• Prioritization is crucial.",
        "",
        "✅ Action items:",
        "• Patricia: Obtain designs today.",
        "• Maxim: Investigate the rejection rate.",
    ).joinToString("\n")

    @Test
    fun `a summary that came back as one line gets its headers and bullets on lines of their own`() {
        assertThat(SummaryFormat.repair(flattened)).isEqualTo(expected)
    }

    @Test
    fun `the same with spaces between the pieces, as a model sometimes writes it`() {
        val spaced = flattened.replace("approvals.Key", "approvals. Key").replace(".• ", ". • ").replace("excluded.• ", "excluded. • ")
            .replace("blockers.Conclusions", "blockers. Conclusions").replace("crucial.✅", "crucial. ✅").replace(":•", ": •")

        assertThat(SummaryFormat.repair(spaced)).isEqualTo(expected)
    }

    @Test
    fun `headers in another language are found too`() {
        val ru = "📝 Краткое содержание — Запись содержит только счёт. Решений нет. Основные моменты: • Нет тем. • Нет решений. " +
            "Выводы: • Это короткая запись. ✅ Пункты действий: • Отсутствуют."

        assertThat(SummaryFormat.repair(ru)).isEqualTo(
            "📝 Краткое содержание — Запись содержит только счёт. Решений нет.\n\nОсновные моменты:\n• Нет тем.\n• Нет решений." +
                "\n\nВыводы:\n• Это короткая запись.\n\n✅ Пункты действий:\n• Отсутствуют.",
        )
    }

    @Test
    fun `a summary that already has its line breaks is left exactly as it is`() {
        assertThat(SummaryFormat.repair(expected)).isEqualTo(expected)
        val withBold = "📝 Summary — Overview.\n\nKey points:\n• **One:** a.\n• **Two:** b.\n\n✅ Action items:\n• Anna: c."
        assertThat(SummaryFormat.repair(withBold)).isEqualTo(withBold)
    }

    @Test
    fun `repairing twice changes nothing more`() {
        val once = SummaryFormat.repair(flattened)

        assertThat(SummaryFormat.repair(once)).isEqualTo(once)
    }

    @Test
    fun `a paragraph, or a text with a single bullet, is not touched`() {
        val paragraph = "The meeting covered the budget. Key points: the cost went up; the plan holds."
        val oneBullet = "Overview.Key points:• The only point."

        assertThat(SummaryFormat.repair(paragraph)).isEqualTo(paragraph)
        assertThat(SummaryFormat.repair(oneBullet)).isEqualTo(oneBullet)
        assertThat(SummaryFormat.repair("")).isEqualTo("")
    }
}
