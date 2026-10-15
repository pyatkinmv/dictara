package com.dictara.gateway.migration

import com.dictara.gateway.SharedTestInfrastructure
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * V35 gives the recordings that lost their length in V24 (the old column was dropped without copying) the end of
 * their last phrase. Tested on a scratch database brought to V34 (what production runs after the redesign), filled,
 * and then migrated, as for V30.
 */
class AudioDurationBackfillMigrationTest {

    private lateinit var dbName: String
    private lateinit var url: String
    private val userId = UUID.randomUUID()

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
        flyway("34").migrate()
    }

    @AfterEach
    fun dropScratchDatabase() {
        withConnection(adminUrl()) { it.createStatement().execute("DROP DATABASE IF EXISTS $dbName WITH (FORCE)") }
    }

    /** A recording with [durationS] on the audio record and, if [phraseEnds] is not null, a transcript with one phrase per end. */
    private fun recording(durationS: Double?, phraseEnds: List<Double>?): UUID {
        val audioId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        withConnection { c ->
            c.prepareStatement("INSERT INTO users(id) VALUES (?) ON CONFLICT DO NOTHING").apply { setObject(1, userId) }.execute()
            c.prepareStatement(
                "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash, duration_s) VALUES (?, ?, 'a.m4a', 'audio/mp4', 1, ?, ?, ?)",
            ).apply { setObject(1, audioId); setObject(2, userId); setString(3, "gs://t/$audioId"); setString(4, "h-$audioId"); setObject(5, durationS) }.execute()
            c.prepareStatement(
                "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
            ).apply { setObject(1, submissionId); setObject(2, userId); setObject(3, audioId) }.execute()
            if (phraseEnds != null) {
                val transcriptId = UUID.randomUUID()
                c.prepareStatement("INSERT INTO transcripts(id, submission_id) VALUES (?, ?)").apply { setObject(1, transcriptId); setObject(2, submissionId) }.execute()
                phraseEnds.forEachIndexed { i, end ->
                    c.prepareStatement("INSERT INTO transcript_segments(transcript_id, idx, start_s, end_s, text) VALUES (?, ?, ?, ?, 'x')")
                        .apply { setObject(1, transcriptId); setInt(2, i); setDouble(3, end - 1); setDouble(4, end) }.execute()
                }
            }
        }
        return audioId
    }

    private fun durationOf(audioId: UUID): Double? = withConnection { c ->
        c.prepareStatement("SELECT duration_s FROM audio_meta WHERE id = ?").apply { setObject(1, audioId) }.executeQuery().use { rs ->
            rs.next(); rs.getDouble(1).takeUnless { rs.wasNull() }
        }
    }

    @Test
    fun `a recording that lost its length gets the end of its last phrase`() {
        val old = recording(durationS = null, phraseEnds = listOf(10.0, 62.5, 1377.7, 900.0)) // not in order on purpose

        flyway().migrate()

        assertThat(durationOf(old)).isEqualTo(1377.7)
    }

    @Test
    fun `a length the transcriber reported is never overwritten`() {
        val known = recording(durationS = 1500.0, phraseEnds = listOf(10.0, 1377.7))

        flyway().migrate()

        assertThat(durationOf(known)).isEqualTo(1500.0)
    }

    @Test
    fun `no phrases, no transcript or only zero-length phrases stay unknown instead of becoming zero`() {
        val emptyTranscript = recording(durationS = null, phraseEnds = emptyList())
        val noTranscript = recording(durationS = null, phraseEnds = null)
        val zeroEnd = recording(durationS = null, phraseEnds = listOf(0.0))

        flyway().migrate()

        assertThat(durationOf(emptyTranscript)).isNull()
        assertThat(durationOf(noTranscript)).isNull()
        assertThat(durationOf(zeroEnd)).isNull()
    }

    @Test
    fun `every affected recording is filled, each from its own transcript`() {
        val a = recording(null, listOf(60.0))
        val b = recording(null, listOf(125.0, 300.0))

        flyway().migrate()

        assertThat(durationOf(a)).isEqualTo(60.0)
        assertThat(durationOf(b)).isEqualTo(300.0)
    }
}
