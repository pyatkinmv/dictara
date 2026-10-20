package com.dictara.gateway.guard

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.springframework.stereotype.Component

/**
 * Marks work that costs money or uses a plan's quota: calls to the summarizer (Gemini), to the transcriber (GPU),
 * writes to audio storage, and the entry points that start any of that (a new transcription, a regenerated summary,
 * the operator's batch).
 *
 * What the mark does: [PaidOperationGuard] refuses the call with [PaidOperationNotAllowedException] when it comes
 * from MCP ([CallOrigin.MCP]). There is no permission that lifts this; an agent holding a token can never start paid
 * work, whatever the token may otherwise do.
 *
 * Put it on a class (every method) or on a method. Classes that carry it are opened by the all-open plugin so that
 * Spring can proxy them. `PaidOperationRulesTest` fails the build when an implementation of a paid port is missing
 * the mark, so a new adapter cannot slip by.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class PaidOperation

/** Where the call being run on this thread came from. Everything is [WEB] (the app, the bot, jobs) unless set. */
enum class CallOrigin {
    WEB,
    MCP,
    ;

    companion object {
        private val current = ThreadLocal<CallOrigin>()

        fun current(): CallOrigin = current.get() ?: WEB

        /**
         * Runs [block] as [origin] on this thread and puts the previous origin back afterwards. The origin does not
         * follow work handed to another thread, which is why the entry points that start such work carry
         * [PaidOperation] themselves (they run on the caller's thread).
         */
        fun <T> runAs(origin: CallOrigin, block: () -> T): T {
            val before = current.get()
            current.set(origin)
            try {
                return block()
            } finally {
                if (before == null) current.remove() else current.set(before)
            }
        }
    }
}

class PaidOperationNotAllowedException(val operation: String) :
    RuntimeException("'$operation' costs money and cannot be started through MCP")

@Aspect
@Component
class PaidOperationGuard {
    @Before("@within(com.dictara.gateway.guard.PaidOperation) || @annotation(com.dictara.gateway.guard.PaidOperation)")
    fun check(joinPoint: JoinPoint) {
        if (CallOrigin.current() == CallOrigin.MCP) {
            throw PaidOperationNotAllowedException(joinPoint.signature.toShortString())
        }
    }
}
