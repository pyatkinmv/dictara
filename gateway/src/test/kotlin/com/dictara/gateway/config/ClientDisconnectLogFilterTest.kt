package com.dictara.gateway.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.spi.FilterReply
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

class ClientDisconnectLogFilterTest {

    private val filter = ClientDisconnectLogFilter()
    private val context = LoggerContext()
    private val tomcat = context.getLogger("org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]")

    private fun decide(logger: ch.qos.logback.classic.Logger = tomcat, level: Level = Level.ERROR, t: Throwable?) =
        filter.decide(null, logger, level, "Servlet.service() threw exception", null, t)

    @Test
    fun `a broken pipe logged by the tomcat container is dropped`() {
        assertThat(decide(t = IOException("Broken pipe"))).isEqualTo(FilterReply.DENY)
        assertThat(decide(t = IOException("Connection reset by peer"))).isEqualTo(FilterReply.DENY)
    }

    @Test
    fun `a disconnect wrapped in another exception is recognized`() {
        assertThat(decide(t = RuntimeException("write failed", IOException("Broken pipe")))).isEqualTo(FilterReply.DENY)
    }

    @Test
    fun `other errors of the container stay visible`() {
        assertThat(decide(t = IOException("Disk full"))).isEqualTo(FilterReply.NEUTRAL)
        assertThat(decide(t = IllegalStateException("boom"))).isEqualTo(FilterReply.NEUTRAL)
        assertThat(decide(t = null)).isEqualTo(FilterReply.NEUTRAL)
    }

    @Test
    fun `our own code logging the same message is never filtered`() {
        val ours = context.getLogger("com.dictara.gateway.controller.TranscribeController")

        assertThat(decide(logger = ours, t = IOException("Broken pipe"))).isEqualTo(FilterReply.NEUTRAL)
    }

    @Test
    fun `only errors are touched`() {
        assertThat(decide(level = Level.WARN, t = IOException("Broken pipe"))).isEqualTo(FilterReply.NEUTRAL)
    }
}
