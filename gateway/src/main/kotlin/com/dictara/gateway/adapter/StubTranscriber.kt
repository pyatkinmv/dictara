package com.dictara.gateway.adapter

import com.dictara.gateway.config.DictaraProperties
import com.dictara.gateway.dto.ProgressInfo
import com.dictara.gateway.model.Segment
import com.dictara.gateway.port.TranscribeParams
import com.dictara.gateway.port.TranscriberJobSnapshot
import com.dictara.gateway.port.TranscriberPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * A transcriber for local development: no models, no GPU, no audio processing. Every job gets one of a
 * handful of ready-made conversations and then plays through the states of the real service over time
 * (`pending` → `processing` with progress → `done`), so the client can be exercised end to end.
 *
 * Progress is computed from the time that has passed since the job was submitted (no threads or timers),
 * which also makes it easy to test. Only successful outcomes exist: there is no failing scenario.
 *
 * Active only under the `local` profile; production uses [com.dictara.gateway.client.TranscriberClient].
 */
@Profile("local")
@Component
class StubTranscriber internal constructor(
    private val timings: DictaraProperties.StubProps,
    private val nowMs: () -> Long,
    private val random: Random,
) : TranscriberPort {

    @Autowired
    constructor(props: DictaraProperties) : this(props.local.stub, System::currentTimeMillis, Random.Default)

    private class Job(
        val startedAtMs: Long,
        val scenario: StubScenario,
        val diarize: Boolean,
        val numSpeakers: Int?,
    )

    private val jobs = ConcurrentHashMap<String, Job>()

    override fun submitByReference(storageUri: String, params: TranscribeParams): String {
        val id = UUID.randomUUID().toString()
        val scenario = StubScenarios.all[random.nextInt(StubScenarios.all.size)]
        jobs[id] = Job(nowMs(), scenario, params.diarize, params.numSpeakers)
        return id
    }

    override fun getJob(jobId: String): TranscriberJobSnapshot {
        val job = jobs[jobId] ?: return TranscriberJobSnapshot(
            status = "failed", progress = null, segments = null, audioDurationS = null,
            detectedLanguage = null, durationS = null, elapsedS = null,
            error = "Job not found", retryable = false,
        )
        val elapsed = (nowMs() - job.startedAtMs) / 1000.0
        val pending = timings.pendingSeconds
        val transcribeEnd = pending + timings.transcribeSeconds
        val diarizeSeconds = if (job.diarize) timings.diarizeSeconds else 0.0
        val end = transcribeEnd + diarizeSeconds
        val total = job.scenario.audioDurationS

        return when {
            elapsed < pending -> TranscriberJobSnapshot(
                status = "pending", progress = null, segments = null, audioDurationS = null,
                detectedLanguage = null, durationS = null, elapsedS = null, error = null,
            )
            elapsed < transcribeEnd -> {
                val fraction = (elapsed - pending) / timings.transcribeSeconds
                processing(elapsed - pending, ProgressInfo("transcribing", processedS = total * fraction, totalS = total))
            }
            elapsed < end -> {
                val fraction = (elapsed - transcribeEnd) / diarizeSeconds
                processing(
                    elapsed - pending,
                    ProgressInfo("diarizing", processedS = total, totalS = total, diarizeProgress = fraction),
                )
            }
            else -> TranscriberJobSnapshot(
                status = "done", progress = null,
                segments = job.scenario.segments(job.diarize, job.numSpeakers),
                audioDurationS = total, detectedLanguage = job.scenario.language,
                durationS = end - pending, elapsedS = null, error = null,
            )
        }
    }

    private fun processing(elapsedS: Double, progress: ProgressInfo) = TranscriberJobSnapshot(
        status = "processing", progress = progress, segments = null, audioDurationS = null,
        detectedLanguage = null, durationS = null, elapsedS = elapsedS, error = null,
    )
}

/** A ready-made conversation: who says what, in order. Timestamps are derived from the text length. */
internal class StubScenario(
    val name: String,
    val language: String,
    /** (speaker index, text); the index becomes `SPEAKER_00`, `SPEAKER_01`… when diarization is on. */
    private val lines: List<Pair<Int, String>>,
) {
    private val timed: List<Triple<Double, Double, Pair<Int, String>>> = run {
        var t = 0.0
        lines.map { line ->
            // About 2.6 words a second, with a short pause between lines.
            val length = maxOf(3.0, line.second.split(' ').size / 2.6)
            val start = t
            t += length + 0.4
            Triple(start, start + length, line)
        }
    }

    val audioDurationS: Double = (timed.last().second + 1.5).let { Math.round(it * 10) / 10.0 }

    val speakerCount: Int get() = lines.map { it.first }.distinct().size

    /** Segments as the transcriber returns them; `speaker` is null when the job did not ask for diarization. */
    fun segments(diarize: Boolean, numSpeakers: Int?): List<Segment> = timed.map { (start, end, line) ->
        val speaker = if (!diarize) null else {
            val index = if (numSpeakers != null && numSpeakers > 0) line.first % numSpeakers else line.first
            "SPEAKER_%02d".format(index)
        }
        Segment(Math.round(start * 10) / 10.0, Math.round(end * 10) / 10.0, line.second, speaker)
    }
}

/** The stub's repertoire: meetings, an interview, a lecture, a voice memo and a Russian phone call. */
internal object StubScenarios {
    val all: List<StubScenario> = listOf(
        StubScenario(
            "Quarterly review", "en",
            listOf(
                0 to "Good morning everyone. Let's get started — we have a lot to cover today. The main focus is Q2 performance and planning the roadmap for the next quarter.",
                1 to "Right. So Q2 was solid overall — revenue is up 18% quarter over quarter. The enterprise segment grew faster than expected, mostly driven by the new integrations we launched in May.",
                2 to "On the product side, we shipped 12 features versus the 9 we planned, but we also accumulated some technical debt in the payments module that we need to address in Q3.",
                0 to "That's great to hear on the feature side. What's the plan for Q3? Are we maintaining the same pace or scaling up the team?",
                1 to "We're planning to hire 4 more engineers by September, which will let us run two parallel development tracks. That should improve throughput significantly without sacrificing quality.",
                2 to "And the technical debt? How much time are we allocating to paying that down before we start adding more features on top?",
                1 to "We're reserving 20% of engineering capacity in July specifically for the payments refactor. It's not glamorous but it's necessary — otherwise we risk reliability issues in the fall when volume spikes.",
                0 to "Makes sense. Alright, let's move on to marketing. What's the Q3 strategy looking like?",
                2 to "We're doubling down on content marketing — SEO-driven articles, a revamped case study library, and a monthly webinar series starting in August.",
                1 to "I like the webinar idea. Can we make sure sales is looped in from the start? They should be co-presenting, not just watching.",
            ),
        ),
        StubScenario(
            "Job interview", "en",
            listOf(
                0 to "Thanks for coming in, Anna. Let's start with a quick overview of your background and what drew you to this role.",
                1 to "Of course. I've spent the last six years in backend engineering, mostly in payments. I was attracted by the scale of the problems you're solving here.",
                0 to "Can you walk me through a system you designed that you're particularly proud of?",
                1 to "Sure. At my last company I redesigned the settlement pipeline. We moved from nightly batches to an event-driven flow, which cut reconciliation time from hours to minutes.",
                0 to "What was the hardest part of that migration?",
                1 to "Idempotency, without a doubt. Every consumer had to handle duplicate events safely, and we had to prove it with replay tests before we could switch traffic over.",
                0 to "That's a strong answer. Do you have any questions for us about the team or the roadmap?",
                1 to "Yes — how do you decide what to build next, and how much room is there for engineers to propose their own projects?",
            ),
        ),
        StubScenario(
            "Product sync", "en",
            listOf(
                0 to "Okay, quick sync on the roadmap. Voice notes shipped last week, so the big question is what comes next.",
                1 to "From the support side, the top request is still speaker names. People want to see who said what without renaming every block by hand.",
                2 to "That fits with the tags work as well. If we get the library right, suggestions become almost free.",
                0 to "Agreed. Let's put the library first and treat suggestions as a follow-up once we see how it is used.",
                1 to "I can pull together feedback from the last ten customer calls so we have real examples to design against.",
                2 to "And I'll sketch the API changes this week so the client work can start in parallel.",
            ),
        ),
        StubScenario(
            "Economics lecture", "en",
            listOf(
                0 to "Today we continue with the theory of comparative advantage. Last time we saw why countries trade at all; now we ask who should produce what.",
                0 to "Suppose one country is better at producing both wine and cloth. Absolute advantage says it should make everything itself. Ricardo showed that this intuition is wrong.",
                0 to "What matters is the opportunity cost. If producing a unit of wine costs the country less cloth than it costs its neighbour, it has the comparative advantage in wine.",
                0 to "Both countries gain if each specialises where its opportunity cost is lower and then trades. The gains come from the difference in costs, not from being better overall.",
                0 to "Next week we will look at the assumptions behind this result and at what happens when they fail, for example with transport costs or when factors of production cannot move.",
            ),
        ),
        StubScenario(
            "Voice memo", "en",
            listOf(
                0 to "Note to self: idea for the onboarding flow. Right now people land on an empty list and don't know what to do.",
                0 to "What if the first screen offered a sample recording, already transcribed, so they can see the tabs, the summary and the speakers before uploading anything of their own?",
                0 to "We could reuse the demo dialog from the design. It only needs a flag so it doesn't count towards the monthly limit.",
                0 to "Also worth checking: does the empty state mention the supported formats? I think it should, and the size limit too.",
            ),
        ),
        StubScenario(
            "Разговор с клиентом", "ru",
            listOf(
                0 to "Добрый день! Меня зовут Мария, я из отдела поддержки. Вы оставляли заявку по поводу выгрузки транскриптов.",
                1 to "Да, здравствуйте. Мы записываем еженедельные встречи, и хотелось бы получать текст сразу после загрузки файла.",
                0 to "Понимаю. Сейчас результат появляется в списке, как только обработка закончится. Можно также скачать текст или архив со всеми записями.",
                1 to "А можно ли автоматически проставлять теги, например «встреча» или «интервью»?",
                0 to "Такая возможность как раз в разработке: для каждого тега вы задаёте описание, а система предлагает подходящие теги для новых записей.",
                1 to "Отлично, тогда мы подождём. И ещё вопрос: сколько длится обработка часовой записи?",
                0 to "Обычно несколько минут. Прогресс виден прямо в списке, так что можно закрыть страницу и вернуться позже.",
            ),
        ),
    )
}
