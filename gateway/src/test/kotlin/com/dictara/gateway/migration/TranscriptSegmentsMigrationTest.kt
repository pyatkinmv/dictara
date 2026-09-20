package com.dictara.gateway.migration

import com.dictara.gateway.SharedTestInfrastructure
import com.dictara.gateway.model.Segment
import com.dictara.gateway.util.TranscriptFormatter
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * V30 moves every phrase of every transcript out of the JSON cell `transcripts.segments` into
 * `transcript_segments`. This is a migration of production data, so it is tested on a scratch database that
 * is first brought to V29 (what production is on), filled with old-style data, and only then migrated.
 */
class TranscriptSegmentsMigrationTest {

    private val json = ObjectMapper()
    private lateinit var dbName: String
    private lateinit var url: String

    private fun adminUrl(): String = SharedTestInfrastructure.jdbcUrl
    private fun urlFor(db: String): String = adminUrl().replace(Regex("(jdbc:postgresql://[^/]+/)([^?]+)"), "$1$db")

    private fun <T> withConnection(target: String = url, block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(target, SharedTestInfrastructure.dbUsername, SharedTestInfrastructure.dbPassword).use(block)

    private fun flyway(target: String? = null): Flyway = Flyway.configure()
        .dataSource(url, SharedTestInfrastructure.dbUsername, SharedTestInfrastructure.dbPassword)
        .apply { if (target != null) target(target) }
        .load()

    @BeforeEach
    fun createScratchDatabase() {
        dbName = "migration_test_" + UUID.randomUUID().toString().replace("-", "")
        withConnection(adminUrl()) { it.createStatement().execute("CREATE DATABASE $dbName") }
        url = urlFor(dbName)
        flyway("29").migrate() // where production is
    }

    @AfterEach
    fun dropScratchDatabase() {
        withConnection(adminUrl()) { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName WITH (FORCE)") }
    }

    private val userId = UUID.randomUUID()

    /** A transcript as V29 stored it: the phrases as one JSON value (or none at all when [segments] is null). */
    private fun legacyTranscript(name: String, segments: String?): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val transcriptId = UUID.randomUUID()
        withConnection { c ->
            c.prepareStatement("INSERT INTO users(id) VALUES (?) ON CONFLICT DO NOTHING").apply { setObject(1, userId) }.execute()
            c.prepareStatement(
                "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, ?, 'audio/mp4', 1, ?, ?)",
            ).apply { setObject(1, audioId); setObject(2, userId); setString(3, name); setString(4, "gs://t/$audioId"); setString(5, "h-$audioId") }.execute()
            c.prepareStatement(
                "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
            ).apply { setObject(1, submissionId); setObject(2, userId); setObject(3, audioId) }.execute()
            c.prepareStatement("INSERT INTO transcripts(id, submission_id, segments) VALUES (?, ?, ?::jsonb)")
                .apply { setObject(1, transcriptId); setObject(2, submissionId); setString(3, segments) }.execute()
        }
        return transcriptId
    }

    private class Row(val idx: Int, val start: Double, val end: Double, val text: String, val voice: String?, val speakerId: UUID?)

    private fun rows(transcriptId: UUID): List<Row> = withConnection { c ->
        c.prepareStatement("SELECT idx, start_s, end_s, text, voice, speaker_id FROM transcript_segments WHERE transcript_id = ? ORDER BY idx")
            .apply { setObject(1, transcriptId) }.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Row(rs.getInt(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID::class.java)))
                }
            }
    }

    private fun jsonOf(transcriptId: UUID): String? = withConnection { c ->
        c.prepareStatement("SELECT segments::text FROM transcripts WHERE id = ?").apply { setObject(1, transcriptId) }.executeQuery()
            .use { rs -> rs.next(); rs.getString(1) }
    }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T =
        withConnection { c -> c.createStatement().executeQuery(sql).use { rs -> rs.next(); read(rs) } }

    private fun schemaVersion(): String = query("SELECT max(version::int)::text FROM flyway_schema_history WHERE version IS NOT NULL") { it.getString(1) }

    private fun tableExists(name: String) = query("SELECT to_regclass('public.$name') IS NOT NULL") { it.getBoolean(1) }

    // ── The data arrives intact ────────────────────────────────────────────────

    @Test
    fun `every phrase is copied with its position, times, text and voice`() {
        val id = legacyTranscript(
            "meeting.m4a",
            """[
               {"start": 0, "end": 30.5, "text": "Good morning everyone.", "speaker": "SPEAKER_00"},
               {"start": 34, "end": 70, "text": "Right. Q2 was solid.", "speaker": "SPEAKER_01"},
               {"start": 72.25, "end": 80, "text": "Yes.", "speaker": "SPEAKER_00"}
            ]""",
        )

        flyway().migrate()

        val rows = rows(id)
        assertThat(rows.map { it.idx }).containsExactly(0, 1, 2)
        assertThat(rows.map { it.text }).containsExactly("Good morning everyone.", "Right. Q2 was solid.", "Yes.")
        assertThat(rows.map { it.start }).containsExactly(0.0, 34.0, 72.25)
        assertThat(rows.map { it.end }).containsExactly(30.5, 70.0, 80.0)
        assertThat(rows.map { it.voice }).containsExactly("SPEAKER_00", "SPEAKER_01", "SPEAKER_00")
        assertThat(rows.map { it.speakerId }).containsOnlyNulls()
    }

    @Test
    fun `phrases without diarization have no voice, whether the speaker is null or missing`() {
        val id = legacyTranscript("memo.mp3", """[{"start": 0, "end": 1, "text": "a", "speaker": null}, {"start": 1, "end": 2, "text": "b"}]""")

        flyway().migrate()

        assertThat(rows(id).map { it.voice }).containsOnlyNulls()
        assertThat(rows(id)).hasSize(2)
    }

    @Test
    fun `text survives as it is, with quotes, newlines, emoji, other scripts and long lines`() {
        val texts = listOf(
            "He said \"hello\" and left.",
            "line one\nline two\ttabbed",
            "emoji 🎧 and CJK 会议 and Cyrillic встреча",
            "back\\slash and 'single' and <tags> & ampersand",
            "  padded with spaces  ",
            "",
            "x".repeat(20_000),
            "separator  inside",
        )
        val segments = texts.mapIndexed { i, t -> """{"start": $i, "end": ${i + 1}, "text": ${json.writeValueAsString(t)}, "speaker": "SPEAKER_00"}""" }
            .joinToString(prefix = "[", postfix = "]")
        val id = legacyTranscript("odd.mp3", segments)

        flyway().migrate()

        assertThat(rows(id).map { it.text }).isEqualTo(texts)
    }

    @Test
    fun `an empty array, a JSON null and no value at all give no rows and do not fail`() {
        val empty = legacyTranscript("empty.mp3", "[]")
        val jsonNull = legacyTranscript("null.mp3", "null")
        val sqlNull = legacyTranscript("sqlnull.mp3", null)

        flyway().migrate()

        for (id in listOf(empty, jsonNull, sqlNull)) assertThat(rows(id)).isEmpty()
    }

    @Test
    fun `a long transcript keeps its order`() {
        val segments = (0 until 4_000).joinToString(prefix = "[", postfix = "]") {
            """{"start": $it.5, "end": ${it + 1}.25, "text": "phrase $it", "speaker": "SPEAKER_0${it % 3}"}"""
        }
        val id = legacyTranscript("long.mp3", segments)

        flyway().migrate()

        val rows = rows(id)
        assertThat(rows).hasSize(4_000)
        assertThat(rows.map { it.text }).isEqualTo((0 until 4_000).map { "phrase $it" })
        assertThat(rows.map { it.idx }).isEqualTo((0 until 4_000).toList())
    }

    @Test
    fun `the formatted text, which the bot and the txt download are built from, is identical before and after`() {
        val samples = listOf(
            """[{"start": 0, "end": 30.5, "text": "Good morning.", "speaker": "SPEAKER_00"}, {"start": 3671.789, "end": 3700, "text": "Later.", "speaker": null}]""",
            "[]",
            """[{"start": 0.001, "end": 0.999, "text": "tiny", "speaker": "SPEAKER_11"}]""",
            (0 until 500).joinToString(prefix = "[", postfix = "]") { """{"start": ${it * 7.3}, "end": ${it * 7.3 + 6.1}, "text": "t$it", "speaker": "SPEAKER_0${it % 4}"}""" },
        )
        val ids = samples.mapIndexed { i, s -> legacyTranscript("f$i.mp3", s) }
        val before = ids.map { TranscriptFormatter.format(json.readTree(jsonOf(it))) }

        flyway().migrate()

        val after = ids.map { id ->
            TranscriptFormatter.format(rows(id).map { Segment(it.start, it.end, it.text, it.voice) })
        }
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `the old JSON column is left exactly as it was`() {
        val id = legacyTranscript("keep.mp3", """[{"start": 0, "end": 1, "text": "a", "speaker": "SPEAKER_00"}]""")
        val before = jsonOf(id)

        flyway().migrate()

        assertThat(jsonOf(id)).isEqualTo(before)
    }

    @Test
    fun `deleting a transcript deletes its phrases, and the tables are empty of speakers to begin with`() {
        val id = legacyTranscript("gone.mp3", """[{"start": 0, "end": 1, "text": "a", "speaker": "SPEAKER_00"}]""")
        flyway().migrate()
        assertThat(rows(id)).hasSize(1)

        withConnection { it.prepareStatement("DELETE FROM transcripts WHERE id = ?").apply { setObject(1, id) }.execute() }

        assertThat(rows(id)).isEmpty()
        assertThat(query("SELECT count(*) FROM transcript_voices") { it.getInt(1) }).isZero()
    }

    @Test
    fun `deleting a library speaker clears them from phrases and voices instead of failing`() {
        val id = legacyTranscript("s.mp3", """[{"start": 0, "end": 1, "text": "a", "speaker": "SPEAKER_00"}]""")
        flyway().migrate()
        val speaker = UUID.randomUUID()
        withConnection { c ->
            c.prepareStatement("INSERT INTO speakers(id, user_id, name) VALUES (?, ?, 'Maria')").apply { setObject(1, speaker); setObject(2, userId) }.execute()
            c.prepareStatement("UPDATE transcript_segments SET speaker_id = ? WHERE transcript_id = ?").apply { setObject(1, speaker); setObject(2, id) }.execute()
            c.prepareStatement("INSERT INTO transcript_voices(transcript_id, voice, speaker_id) VALUES (?, 'SPEAKER_00', ?)").apply { setObject(1, id); setObject(2, speaker) }.execute()
        }

        withConnection { it.prepareStatement("DELETE FROM speakers WHERE id = ?").apply { setObject(1, speaker) }.execute() }

        assertThat(rows(id).single().speakerId).isNull() // the phrase stays, nobody is named
        assertThat(query("SELECT count(*) FROM transcript_voices") { it.getInt(1) }).isZero()
    }

    @Test
    fun `many transcripts are migrated together and the counts add up`() {
        val ids = (1..40).map { n ->
            legacyTranscript("m$n.mp3", (0 until n).joinToString(prefix = "[", postfix = "]") { """{"start": $it, "end": ${it + 1}, "text": "p$it", "speaker": "SPEAKER_00"}""" })
        }

        flyway().migrate()

        assertThat(query("SELECT count(*) FROM transcript_segments") { it.getInt(1) }).isEqualTo((1..40).sum())
        ids.forEachIndexed { i, id -> assertThat(rows(id)).hasSize(i + 1) }
    }

    // ── Dirty data stops the migration, and nothing is left half done ──────────

    private fun assertRefused(segments: String?, messagePart: String) {
        legacyTranscript("good.mp3", """[{"start": 0, "end": 1, "text": "fine", "speaker": null}]""")
        legacyTranscript("dirty.mp3", segments)

        assertThatThrownBy { flyway().migrate() }
            .isInstanceOf(FlywayException::class.java)
            .hasMessageContaining(messagePart)

        // Everything is undone: still V29, no new tables, the JSON as it was.
        assertThat(schemaVersion()).isEqualTo("29")
        assertThat(tableExists("transcript_segments")).isFalse()
        assertThat(tableExists("transcript_voices")).isFalse()
    }

    @Test
    fun `a phrase without text stops the migration`() = assertRefused("""[{"start": 0, "end": 1, "speaker": null}]""", "V30")

    @Test
    fun `a start that is not a number stops the migration, even one that looks like a number`() =
        assertRefused("""[{"start": "12", "end": 13, "text": "x", "speaker": null}]""", "V30")

    @Test
    fun `a missing end stops the migration`() = assertRefused("""[{"start": 0, "text": "x", "speaker": null}]""", "V30")

    @Test
    fun `a phrase that is not an object stops the migration`() = assertRefused("""["just a string"]""", "V30")

    @Test
    fun `a text that is not a string stops the migration`() = assertRefused("""[{"start": 0, "end": 1, "text": 5, "speaker": null}]""", "V30")

    @Test
    fun `a speaker that is not a string or null stops the migration`() =
        assertRefused("""[{"start": 0, "end": 1, "text": "x", "speaker": 3}]""", "V30")

    @Test
    fun `segments that are not an array stop the migration`() = assertRefused("\"oops\"", "not a JSON array")

    @Test
    fun `once the data is fixed the same migration goes through`() {
        val id = legacyTranscript("dirty.mp3", """[{"start": 0, "end": 1, "speaker": null}]""")
        assertThatThrownBy { flyway().migrate() }.isInstanceOf(FlywayException::class.java)

        withConnection {
            it.prepareStatement("UPDATE transcripts SET segments = ?::jsonb WHERE id = ?")
                .apply { setString(1, """[{"start": 0, "end": 1, "text": "fixed", "speaker": null}]"""); setObject(2, id) }.execute()
        }
        flyway().migrate()

        assertThat(rows(id).map { it.text }).containsExactly("fixed")
        assertThat(tableExists("transcript_segments")).isTrue()
    }
}
