package com.dictara.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.ZoneId

/** Where a recording's time comes from: the file name (read in an assumed zone) and the container's own tag. */
class RecordedAtTest {

    private val dubai = ZoneId.of("Asia/Dubai")
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val json = ObjectMapper()

    private fun name(n: String) = RecordedAt.parseFileName(n, dubai, now)
    private fun meta(t: String?) = RecordedAt.parseMetadata(
        json.readTree(if (t == null) """{"tags":{}}""" else """{"tags":{"creation_time":"$t"}}"""), now,
    )

    // -- file names ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(
        "20260608_213900.m4a, 2026-06-08T17:39:00Z",
        "20260321_150706 (2).m4a, 2026-03-21T11:07:06Z",
        "VID_20260608-213900.mp4, 2026-06-08T17:39:00Z",
        "2026-06-08 21.39.00.m4a, 2026-06-08T17:39:00Z",
        "Screen Recording 2026-06-08 at 21.39.00.mov, 2026-06-08T17:39:00Z",
        "2026-06-08T21:39:00 call.wav, 2026-06-08T17:39:00Z",
    )
    fun `a date and time in the name is read as local time in the assumed zone`(file: String, utc: String) {
        assertThat(name(file)).isEqualTo(Instant.parse(utc))
    }

    @ParameterizedTest
    @CsvSource(
        "PTT-20260608-WA0001.opus, 2026-06-08T08:00:00Z",
        "WhatsApp Audio 2026-06-08.m4a, 2026-06-08T08:00:00Z",
        "20260608_256100.m4a, 2026-06-08T08:00:00Z",
    )
    fun `a day alone (or a good day with a time that cannot be) becomes noon of that day, which keeps the day right`(file: String, utc: String) {
        assertThat(name(file)).isEqualTo(Instant.parse(utc))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "audio_1792237129832.oga", "dictara-17932016705954596228.oga", "New Recording 85.m4a", "револют финал.m4a",
        "Крис 22.04.m4a", "IMG_2258.MP4", "recording.m4a", "",
    ])
    fun `a name with no date, or a number that only looks like one, gives nothing`(file: String) {
        assertThat(name(file)).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["20260230_101010.m4a", "20261340_101010.m4a", "19990101_101010.m4a"])
    fun `an impossible or implausible date gives nothing`(file: String) {
        assertThat(name(file)).isNull()
    }

    @Test
    fun `a date in the future is not a recording time`() {
        assertThat(name("20270101_101010.m4a")).isNull()
        assertThat(name("20261019_101010.m4a")).isNotNull() // a day or so ahead: a clock set a little wrong
    }

    // -- the container's creation_time ---------------------------------------------------------------------

    @Test
    fun `creation_time of the container is taken as it is, in UTC`() {
        assertThat(meta("2026-06-22T14:41:00.000000Z")).isEqualTo(Instant.parse("2026-06-22T14:41:00Z"))
        assertThat(meta("2025-10-24T10:07:01+04:00")).isEqualTo(Instant.parse("2025-10-24T06:07:01Z"))
    }

    @Test
    fun `no tag, no tags, no metadata or text that is not a time give nothing`() {
        assertThat(meta(null)).isNull()
        assertThat(RecordedAt.parseMetadata(null, now)).isNull()
        assertThat(RecordedAt.parseMetadata(json.readTree("""{"codec":"aac"}"""), now)).isNull()
        assertThat(meta("yesterday")).isNull()
        assertThat(RecordedAt.parseMetadata(json.readTree("""{"tags":{"creation_time":5}}"""), now)).isNull()
    }

    @Test
    fun `a recorder with no clock (1904, 1970) or one far ahead is not believed`() {
        assertThat(meta("1904-01-01T00:00:00Z")).isNull()
        assertThat(meta("1970-01-01T00:00:00Z")).isNull()
        assertThat(meta("2030-01-01T00:00:00Z")).isNull()
    }
}
