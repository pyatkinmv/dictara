package com.dictara.gateway.util

import java.util.Locale

/**
 * The color of a tag, worked out from its name: the same name always gets the same color and nothing is
 * stored. Colors are spread over the whole hue circle (with a few saturations and lightnesses), so there
 * are hundreds of distinct ones, and all of them are dark enough for white text.
 *
 * Renaming a tag therefore changes its color; that was accepted in favour of storing nothing.
 */
object TagColors {
    private val saturations = intArrayOf(60, 70, 80)
    private val lightnesses = intArrayOf(38, 42, 46)

    /** `#RRGGBB` for the tag called [name] (case does not matter). */
    fun of(name: String): String {
        val hash = fnv1a(name.lowercase(Locale.ROOT))
        val hue = ((hash ushr 8) % 360).toDouble()
        val saturation = saturations[((hash ushr 2) and 0x3) % saturations.size] / 100.0
        var lightness = lightnesses[((hash ushr 4) and 0x3) % lightnesses.size] / 100.0
        // Yellows and greens look bright at the same lightness as blues; darken until white text is readable.
        while (lightness > 0.2 && contrastWithWhite(hue, saturation, lightness) < MIN_CONTRAST) lightness -= 0.02
        return hslToHex(hue, saturation, lightness)
    }

    /** WCAG's lowest contrast for large or bold text is 3; pills are small bold text, so a little more. */
    private const val MIN_CONTRAST = 3.4

    private fun contrastWithWhite(hue: Double, saturation: Double, lightness: Double): Double {
        val (r, g, b) = rgb(hue, saturation, lightness)
        fun linear(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        return 1.05 / (luminance + 0.05)
    }

    /** 32-bit FNV-1a over the UTF-8 bytes: fixed by its definition, unlike anything tied to a JVM version. */
    private fun fnv1a(text: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
        }
        return hash and 0x7FFFFFFF
    }

    /** Red, green and blue in 0..1. */
    private fun rgb(hue: Double, saturation: Double, lightness: Double): Triple<Double, Double, Double> {
        val chroma = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val x = chroma * (1 - kotlin.math.abs((hue / 60) % 2 - 1))
        val m = lightness - chroma / 2
        val (r, g, b) = when {
            hue < 60 -> Triple(chroma, x, 0.0)
            hue < 120 -> Triple(x, chroma, 0.0)
            hue < 180 -> Triple(0.0, chroma, x)
            hue < 240 -> Triple(0.0, x, chroma)
            hue < 300 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return Triple(r + m, g + m, b + m)
    }

    private fun hslToHex(hue: Double, saturation: Double, lightness: Double): String {
        val (r, g, b) = rgb(hue, saturation, lightness)
        fun channel(v: Double) = (v * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(channel(r), channel(g), channel(b))
    }
}
