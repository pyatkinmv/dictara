package com.dictara.gateway.adapter

import com.dictara.gateway.config.DictaraProperties.StubProps
import com.dictara.gateway.port.TranscribeParams
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset.offset
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StubTranscriberTest {

    private var now = 1_000_000L
    private val timings = StubProps(pendingSeconds = 2.0, transcribeSeconds = 10.0, diarizeSeconds = 5.0)

    private fun stub(seed: Int = 1) = StubTranscriber(timings, { now }, Random(seed))

    private fun params(diarize: Boolean = true, numSpeakers: Int? = null, language: String = "auto") =
        TranscribeParams("small", language, diarize, numSpeakers, "a.mp3")

    private fun advance(seconds: Double) {
        now += (seconds * 1000).toLong()
    }

    @Test
    fun `a job is pending, then processing with progress, then done`() {
        val stub = stub()
        val id = stub.submitByReference("db://audio/x", params())

        assertThat(stub.getJob(id).status).isEqualTo("pending")
        assertThat(stub.getJob(id).progress).isNull()

        advance(2.0) // the transcriber picks it up
        val started = stub.getJob(id)
        assertThat(started.status).isEqualTo("processing")
        assertThat(started.progress!!.phase).isEqualTo("transcribing")
        assertThat(started.progress!!.processedS).isEqualTo(0.0)

        advance(5.0) // half way through transcribing
        val half = stub.getJob(id)
        assertThat(half.progress!!.phase).isEqualTo("transcribing")
        assertThat(half.progress!!.processedS!!).isCloseTo(half.progress!!.totalS!! / 2, offset(0.01))
        assertThat(half.elapsedS).isEqualTo(5.0)

        advance(5.0) // transcribing is done, diarizing starts
        val diarizing = stub.getJob(id)
        assertThat(diarizing.status).isEqualTo("processing")
        assertThat(diarizing.progress!!.phase).isEqualTo("diarizing")
        assertThat(diarizing.progress!!.diarizeProgress).isEqualTo(0.0)

        advance(2.5)
        assertThat(stub.getJob(id).progress!!.diarizeProgress).isEqualTo(50.0)

        advance(2.5)
        val done = stub.getJob(id)
        assertThat(done.status).isEqualTo("done")
        assertThat(done.progress).isNull()
        assertThat(done.segments).isNotEmpty()
        assertThat(done.detectedLanguage).isNotBlank()
        assertThat(done.audioDurationS!!).isGreaterThan(done.segments!!.last().end)
        assertThat(done.durationS).isEqualTo(15.0)
    }

    @Test
    fun `without diarization there is no diarizing phase and no speakers`() {
        val stub = stub()
        val id = stub.submitByReference("db://audio/x", params(diarize = false))

        advance(12.0) // 2 s pending + 10 s transcribing
        val done = stub.getJob(id)
        assertThat(done.status).isEqualTo("done")
        assertThat(done.segments!!.map { it.speaker }).containsOnlyNulls()
    }

    @Test
    fun `with diarization every segment has a speaker label`() {
        val stub = stub()
        val id = stub.submitByReference("db://audio/x", params(diarize = true))
        advance(17.0)

        val speakers = stub.getJob(id).segments!!.map { it.speaker }
        assertThat(speakers).doesNotContainNull()
        assertThat(speakers).allMatch { it!!.matches(Regex("SPEAKER_\\d\\d")) }
    }

    @Test
    fun `the number of speakers asked for caps the labels`() {
        // Find a seed that picks a conversation with three speakers.
        val seed = (0..50).first { s ->
            val probe = stub(s)
            val id = probe.submitByReference("u", params())
            advance(17.0)
            probe.getJob(id).segments!!.map { it.speaker }.distinct().size == 3
        }
        val stub = stub(seed)
        val id = stub.submitByReference("u", params(numSpeakers = 2))
        advance(17.0)

        assertThat(stub.getJob(id).segments!!.map { it.speaker }.distinct())
            .containsExactlyInAnyOrder("SPEAKER_00", "SPEAKER_01")
    }

    @Test
    fun `segments are ordered in time without overlaps`() {
        StubScenarios.all.forEachIndexed { index, _ ->
            val s = stub(index)
            val id = s.submitByReference("u", params())
            advance(17.0)
            val segments = s.getJob(id).segments!!
            segments.forEach { assertThat(it.end).isGreaterThan(it.start) }
            segments.zipWithNext().forEach { (a, b) -> assertThat(b.start).isGreaterThanOrEqualTo(a.end) }
        }
    }

    @Test
    fun `there are five or six conversations in English and Russian and the pick is random`() {
        assertThat(StubScenarios.all.size).isBetween(5, 6)
        assertThat(StubScenarios.all.map { it.language }.toSet()).contains("en", "ru")
        assertThat(StubScenarios.all.map { it.name }).doesNotHaveDuplicates()

        val picked = (0 until 40).map { seed ->
            val s = stub(seed)
            val id = s.submitByReference("u", params(diarize = false))
            advance(12.0)
            s.getJob(id).segments!!.first().text
        }.toSet()
        assertThat(picked.size).isGreaterThan(1)
    }

    @Test
    fun `an unknown job has failed for good`() {
        val job = stub().getJob("nope")
        assertThat(job.status).isEqualTo("failed")
        assertThat(job.retryable).isFalse()
        assertThat(job.error).isNotBlank()
    }

    @Test
    fun `jobs are independent`() {
        val stub = stub()
        val first = stub.submitByReference("u", params())
        advance(8.0)
        val second = stub.submitByReference("u", params())

        assertThat(stub.getJob(first).status).isEqualTo("processing")
        assertThat(stub.getJob(second).status).isEqualTo("pending")
    }
}
