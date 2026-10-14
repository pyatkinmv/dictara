package com.dictara.gateway

import com.dictara.gateway.service.JwtTokenProvider
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.AudioStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.io.ByteArrayInputStream
import java.util.UUID

private fun <T> any(): T { ArgumentMatchers.any<T>(); @Suppress("UNCHECKED_CAST") return null as T }

/**
 * `POST /transcriptions/{id}/audio-token` and `GET /transcriptions/{id}/audio` — the scoped playback token
 * (why it exists instead of reusing the session JWT, and the still-open gap that it does not fully close, is
 * written up in `docs/redesign-plan.md`, plan item 12) and byte-range serving for the `<audio>` player.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class AudioPlaybackIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @MockBean lateinit var audioStorage: AudioStorage

    private lateinit var userId: UUID
    private val audioBytes = "0123456789".toByteArray() // 10 bytes — easy to reason about in range tests

    @BeforeEach
    fun setUp() {
        userId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", userId)
        given(audioStorage.openFrom(any(), ArgumentMatchers.anyLong())).willAnswer { inv ->
            val offset = (inv.arguments[1] as Long).toInt()
            ByteArrayInputStream(audioBytes, offset, audioBytes.size - offset)
        }
    }

    private fun addSubmission(owner: UUID = userId, storageUri: String? = "gs://test/audio.mp3"): UUID {
        val audioId = UUID.randomUUID()
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO audio_meta(id, user_id, original_name, content_type, size_bytes, storage_uri, content_hash) VALUES (?, ?, 'call.mp3', 'audio/mpeg', ?, ?, ?)",
            audioId, owner, audioBytes.size.toLong(), storageUri, "hash-$audioId",
        )
        jdbcTemplate.update(
            "INSERT INTO submissions(id, user_id, audio_id, model, language_hint, diarize, summary_mode, source, status) VALUES (?, ?, ?, 'fast', 'auto', false, 'off', 'web', 'done')",
            id, owner, audioId,
        )
        return id
    }

    private fun issueToken(id: UUID, asUser: UUID = userId) = rest.exchange(
        "/transcriptions/$id/audio-token", HttpMethod.POST,
        HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(jwt.issue(asUser)) }),
        Map::class.java,
    )

    @Test
    fun `issuing a token for someone else's transcription is a 404`() {
        val id = addSubmission()
        val otherUser = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO users(id) VALUES (?)", otherUser)

        val resp = issueToken(id, asUser = otherUser)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a fresh token streams the whole file when there is no Range header`() {
        val id = addSubmission()
        val token = issueToken(id).body!!["token"] as String

        val resp = rest.getForEntity("/transcriptions/$id/audio?token=$token", ByteArray::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resp.headers.contentType.toString()).isEqualTo("audio/mpeg")
        assertThat(resp.headers.getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes")
        assertThat(resp.body).isEqualTo(audioBytes)
    }

    @Test
    fun `a Range header returns exactly that slice as 206`() {
        val id = addSubmission()
        val token = issueToken(id).body!!["token"] as String

        val resp = rest.exchange(
            "/transcriptions/$id/audio?token=$token", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set(HttpHeaders.RANGE, "bytes=3-5") }),
            ByteArray::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.PARTIAL_CONTENT)
        assertThat(resp.headers.getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 3-5/10")
        assertThat(String(resp.body!!, Charsets.UTF_8)).isEqualTo("345")
    }

    @Test
    fun `an open-ended Range (seeking near the end) returns everything from that offset`() {
        val id = addSubmission()
        val token = issueToken(id).body!!["token"] as String

        val resp = rest.exchange(
            "/transcriptions/$id/audio?token=$token", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set(HttpHeaders.RANGE, "bytes=7-") }),
            ByteArray::class.java,
        )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.PARTIAL_CONTENT)
        assertThat(resp.headers.getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 7-9/10")
        assertThat(String(resp.body!!, Charsets.UTF_8)).isEqualTo("789")
    }

    @Test
    fun `a token minted for one transcription cannot stream another's audio`() {
        val id = addSubmission()
        val otherId = addSubmission()
        val token = issueToken(id).body!!["token"] as String

        val resp = rest.getForEntity("/transcriptions/$otherId/audio?token=$token", String::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the ordinary session token does not work here, and an audio token does not work as a session token`() {
        val id = addSubmission()
        val audioToken = issueToken(id).body!!["token"] as String

        val withSessionToken = rest.getForEntity("/transcriptions/$id/audio?token=${jwt.issue(userId)}", String::class.java)
        assertThat(withSessionToken.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        val elsewhereWithAudioToken = rest.exchange(
            "/transcriptions/$id", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { setBearerAuth(audioToken) }),
            String::class.java,
        )
        assertThat(elsewhereWithAudioToken.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `no audio stored yet is a 404`() {
        val id = addSubmission(storageUri = null)
        val token = issueToken(id).body!!["token"] as String

        val resp = rest.getForEntity("/transcriptions/$id/audio?token=$token", String::class.java)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
