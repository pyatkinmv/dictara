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

    private fun ts(seconds: Double): String {
        val h  = (seconds / 3600).toInt()
        val m  = ((seconds % 3600) / 60).toInt()
        val s  = (seconds % 60).toInt()
        val ms = ((seconds % 1) * 1000).toInt()
        return "%02d:%02d:%02d.%03d".format(h, m, s, ms)
    }
}
