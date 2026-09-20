package com.dictara.gateway

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * Base class for integration tests that share [SharedTestInfrastructure] (one PostgreSQL container
 * + one WireMock server). Subclasses automatically get:
 *   - [DynamicPropertySource] wiring to the shared infra (Spring caches one context for all subclasses
 *     that also share the same @MockBean set)
 *   - A [@BeforeEach][BeforeEach] that resets WireMock and truncates all submission-related tables
 *     atomically via CASCADE before each test
 */
abstract class AbstractSharedContextIntegrationTest {

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    protected val wireMock get() = SharedTestInfrastructure.wireMock

    /**
     * Stores a transcript the way the gateway does: [segments] (a JSON array of `{start, end, text, speaker}`)
     * both as the JSON cell and as one row per phrase. A null or non-array [segments] gives no phrases.
     */
    protected fun insertTranscript(transcriptId: UUID, submissionId: UUID, segments: String?, title: String? = null, detectedLanguage: String? = null) {
        // TODO(drop-json-segments): once transcripts.segments is dropped, only the rows remain.
        jdbcTemplate.update(
            "INSERT INTO transcripts(id, submission_id, segments, title, detected_language) VALUES (?, ?, ?::jsonb, ?, ?)",
            transcriptId, submissionId, segments, title, detectedLanguage,
        )
        jdbcTemplate.update(
            """
            INSERT INTO transcript_segments (transcript_id, idx, start_s, end_s, text, voice)
            SELECT t.id, (e.ord - 1)::int, (e.val ->> 'start')::double precision, (e.val ->> 'end')::double precision,
                   e.val ->> 'text', e.val ->> 'speaker'
            FROM transcripts t, jsonb_array_elements(CASE WHEN jsonb_typeof(t.segments) = 'array' THEN t.segments ELSE '[]'::jsonb END)
                     WITH ORDINALITY AS e(val, ord)
            WHERE t.id = ?
            """.trimIndent(),
            transcriptId,
        )
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun sharedInfraProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { SharedTestInfrastructure.jdbcUrl }
            registry.add("spring.datasource.username") { SharedTestInfrastructure.dbUsername }
            registry.add("spring.datasource.password") { SharedTestInfrastructure.dbPassword }
            registry.add("dictara.transcriber.url") { SharedTestInfrastructure.wireMock.baseUrl() }
            registry.add("dictara.transcriber.poll-interval-ms") { "100" }
        }
    }

    @BeforeEach
    fun cleanSharedState() {
        SharedTestInfrastructure.wireMock.resetAll()
        // Retry on deadlock: OrchestratorService background threads may hold locks on submissions/audio_meta
        // while TRUNCATE CASCADE tries to acquire exclusive locks on both tables simultaneously.
        repeat(5) { attempt ->
            try {
                jdbcTemplate.execute("TRUNCATE audio_meta CASCADE")
                return
            } catch (e: Exception) {
                if (attempt == 4) throw e
                Thread.sleep(300)
            }
        }
    }
}
