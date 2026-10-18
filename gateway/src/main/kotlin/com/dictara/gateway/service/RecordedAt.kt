package com.dictara.gateway.service

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.repository.AudioMetaRepository
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * When a recording was made, as far as the file says. Two sources, the exact one wins:
 *  - `metadata`: the container's `creation_time` tag (UTC) that the transcriber's ffprobe reads from the original file;
 *  - `filename`: a date in the file name (`20260608_213900.m4a`, `Screen Recording 2026-06-08 at 21.39.00.mov`,
 *    `PTT-20260608-WA0001.opus`). A name carries a local wall-clock time without a zone, so it is read in
 *    `dictara.recorded-at.name-zone`; a name with only a day gets noon of that day, which keeps the day right in
 *    nearly every zone.
 * Names made up by the system (`dictara-<digits>`, `audio_<millis>`) carry no recording time and give nothing.
 */
@Component
class RecordedAt(
    props: DictaraProperties,
    private val audioMetaRepo: AudioMetaRepository,
) {
    private val nameZone: ZoneId = ZoneId.of(props.recordedAt.nameZone)

    /** The recording time in the file name, or null when the name has none. */
    fun fromFileName(name: String): Instant? = parseFileName(name, nameZone)

    /** Stores what the file name says for [audioId], unless something better (metadata, a manual value) is there. */
    fun setFromFileName(audioId: UUID, name: String) {
        val at = fromFileName(name) ?: return
        audioMetaRepo.updateRecordedAtFromFileName(audioId, at)
    }

    /** Stores the `creation_time` of the container [metadata] for [audioId]; it wins over a date taken from the name. */
    fun setFromMetadata(audioId: UUID, metadata: JsonNode?) {
        val at = parseMetadata(metadata) ?: return
        audioMetaRepo.updateRecordedAtFromMetadata(audioId, at)
    }

    companion object {
        /** A recorder with no clock writes 1904 or 1970; a clock set wrong can be a day or two ahead. */
        private val EARLIEST = Instant.parse("2000-01-01T00:00:00Z")
        private const val FUTURE_SLACK_DAYS = 2L

        fun parseMetadata(metadata: JsonNode?, now: Instant = Instant.now()): Instant? {
            val text = metadata?.path("tags")?.path("creation_time")?.takeIf { it.isTextual }?.asText() ?: return null
            val at = try {
                OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeException) {
                return null
            }
            return at.takeIf { it >= EARLIEST && it <= now.plusSeconds(FUTURE_SLACK_DAYS * 86_400) }
        }

        private const val DATE = """(20\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])"""
        private const val TIME = """([01]\d|2[0-3])[-_.:]?([0-5]\d)[-_.:]?([0-5]\d)"""

        // 20260608_213900, VID_20260608-213900, 2026-06-08 21.39.00, Screen Recording 2026-06-08 at 21.39.00
        private val DATE_TIME = Regex("""(?<!\d)$DATE(?:[ _T-]|\sat\s)$TIME(?!\d)""")
        // a day alone: PTT-20260608-WA0001, WhatsApp Audio 2026-06-08
        private val DAY_ONLY = Regex("""(?<!\d)$DATE(?!\d)""")

        fun parseFileName(name: String, zone: ZoneId, now: Instant = Instant.now()): Instant? {
            val at = try {
                DATE_TIME.find(name)?.let { m ->
                    val g = m.groupValues.drop(1).map { it.toInt() }
                    LocalDateTime.of(g[0], g[1], g[2], g[3], g[4], g[5]).atZone(zone).toInstant()
                } ?: DAY_ONLY.find(name)?.let { m ->
                    val g = m.groupValues.drop(1).map { it.toInt() }
                    LocalDate.of(g[0], g[1], g[2]).atTime(LocalTime.NOON).atZone(zone).toInstant()
                }
            } catch (_: DateTimeException) {
                null // 30 February and the like
            }
            return at?.takeIf { it >= EARLIEST && it <= now.plusSeconds(FUTURE_SLACK_DAYS * 86_400) }
        }
    }
}
