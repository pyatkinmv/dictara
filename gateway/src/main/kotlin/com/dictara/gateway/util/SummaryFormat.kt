package com.dictara.gateway.util

/**
 * Gemini writes the summary inside a JSON string, and now and then it leaves out the line breaks there: the whole
 * structured summary comes back as one line ("...rollouts.Key points:• Regulatory...• Internal...Conclusions:• ...").
 * The structure is still in the text, so the breaks are put back: every bullet on its own line, and every header
 * (a short phrase ending with a colon, right before a list) on a line of its own after a blank line.
 *
 * Only a summary that is clearly flattened is touched: a good one has about a line break per bullet.
 */
object SummaryFormat {

    private const val BULLET = '•'

    /** A short phrase ending with a colon, after the end of a sentence (or at the start), that is followed by the first bullet of a list. */
    private val header = Regex("(?:(?<=[.!?…)\\]'])|^)[ \\t]*((?:\\S{1,2} )?[^.!?…\\n•]{1,40}:)[ \\t]*\\n• ")

    fun repair(summary: String): String {
        val bullets = summary.count { it == BULLET }
        val lineBreaks = summary.count { it == '\n' }
        if (bullets < 2 || lineBreaks >= bullets / 2) return summary

        val bulletsApart = summary.replace(Regex("[ \\t]*$BULLET[ \\t]*"), "\n• ")
        return header.replace(bulletsApart) { "\n\n${it.groupValues[1].trim()}\n• " }.trim()
    }
}
