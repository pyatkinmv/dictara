package com.dictara.gateway.util

import com.dictara.gateway.model.Segment
import com.fasterxml.jackson.databind.JsonNode

object TranscriptFormatter {

    fun format(segments: List<Segment>): String =
        segments.joinToString("\n") { seg ->
            val ts = "[${ts(seg.start)} --> ${ts(seg.end)}]"
            if (seg.speaker != null) "$ts [${seg.speaker}] ${seg.text}" else "$ts ${seg.text}"
        }

    fun format(node: JsonNode?): String =
        node?.joinToString("\n") { seg ->
            val start = seg["start"].asDouble()
            val end   = seg["end"].asDouble()
            val text  = seg["text"].asText()
            val spk   = seg["speaker"]?.takeIf { !it.isNull }?.asText()
            val ts    = "[${ts(start)} --> ${ts(end)}]"
            if (spk != null) "$ts [$spk] $text" else "$ts $text"
        } ?: ""

    /** A phrase already resolved to who actually said it (a per-phrase mark, else its voice's confirmed speaker,
     *  else the "Speaker N" fallback) — never the raw diarizer label ("SPEAKER_00"). */
    data class ResolvedPhrase(val start: Double, val end: Double, val speakerName: String?, val text: String)

    /**
     * Like [format], but each phrase already carries who actually said it, resolved upstream (see
     * [ResolvedPhrase]) instead of the raw diarizer label — this is what a person reads in the .txt download,
     * Copy and the ZIP export, so it matches the names shown on the page rather than "SPEAKER_00".
     */
    fun formatResolved(phrases: List<ResolvedPhrase>): String =
        phrases.joinToString("\n") { p ->
            val ts = "[${ts(p.start)} --> ${ts(p.end)}]"
            if (p.speakerName != null) "$ts [${p.speakerName}] ${p.text}" else "$ts ${p.text}"
        }

    private fun ts(seconds: Double): String {
        val h  = (seconds / 3600).toInt()
        val m  = ((seconds % 3600) / 60).toInt()
        val s  = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }
}
