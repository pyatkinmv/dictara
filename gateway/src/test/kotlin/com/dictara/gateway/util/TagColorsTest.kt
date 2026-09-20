package com.dictara.gateway.util

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TagColorsTest {

    @Test
    fun `a name always gets the same color, whatever its case`() {
        assertThat(TagColors.of("meeting")).isEqualTo(TagColors.of("meeting"))
        assertThat(TagColors.of("Meeting")).isEqualTo(TagColors.of("meeting"))
    }

    @Test
    fun `the color is a hex triplet`() {
        for (name in listOf("a", "meeting", "встреча", "会议", "q3-planning", "x_y")) {
            assertThat(TagColors.of(name)).describedAs(name).matches("#[0-9A-F]{6}")
        }
    }

    @Test
    fun `the colors are pinned, so a change of the formula does not quietly recolor every tag`() {
        assertThat(TagColors.of("meeting")).isEqualTo("#8F13AE")
    }

    @Test
    fun `many names spread over many colors, not a handful`() {
        val colors = (1..200).map { TagColors.of("tag-$it") }.toSet()

        assertThat(colors.size).isGreaterThan(100)
    }

    @Test
    fun `every color is dark enough for white text`() {
        for (i in 1..500) {
            val hex = TagColors.of("tag-$i")
            val (r, g, b) = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255.0 }
            fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            val luminance = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
            val contrastWithWhite = 1.05 / (luminance + 0.05)
            assertThat(contrastWithWhite).describedAs(hex).isGreaterThan(3.0)
        }
    }
}
