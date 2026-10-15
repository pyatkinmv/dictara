package com.dictara.gateway.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker
import java.io.IOException

/**
 * A browser that closes a connection while audio is streaming (it seeked elsewhere, or the page was left) is normal,
 * but Tomcat reports it as an ERROR "Servlet.service() ... threw exception" with a stack, and that cannot be
 * stopped from the controller: it is logged by the container itself while it handles the failed write. Such entries
 * would drown real errors (and trip any alert on ERROR), so they are dropped here.
 *
 * Narrow on purpose: only ERROR entries of the Tomcat container logger whose exception chain is a client
 * disconnect. Everything else, including every error our own code logs, passes untouched.
 */
class ClientDisconnectLogFilter : TurboFilter() {

    override fun decide(marker: Marker?, logger: Logger, level: Level, format: String?, params: Array<out Any?>?, t: Throwable?): FilterReply =
        if (level == Level.ERROR && t != null && logger.name.startsWith(TOMCAT_CONTAINER_LOGGER) && isClientDisconnect(t)) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }

    private fun isClientDisconnect(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(MAX_CAUSE_DEPTH).any { e ->
        e.javaClass.simpleName in DISCONNECT_EXCEPTIONS ||
            (e is IOException && e.message?.let { m -> DISCONNECT_MESSAGES.any { it in m } } == true)
    }

    private companion object {
        const val TOMCAT_CONTAINER_LOGGER = "org.apache.catalina.core.ContainerBase"
        const val MAX_CAUSE_DEPTH = 10
        val DISCONNECT_EXCEPTIONS = setOf("ClientAbortException", "AsyncRequestNotUsableException")
        val DISCONNECT_MESSAGES = listOf("Broken pipe", "Connection reset by peer", "An established connection was aborted")
    }
}
