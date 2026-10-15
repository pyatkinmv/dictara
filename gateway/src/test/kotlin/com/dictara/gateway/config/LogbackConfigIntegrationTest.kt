package com.dictara.gateway.config

import com.dictara.gateway.AbstractSharedContextIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.io.IOException

/** The filter is only worth anything if `logback-spring.xml` is really picked up by the running application. */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
class LogbackConfigIntegrationTest : AbstractSharedContextIntegrationTest() {

    @Test
    fun `a client disconnect reported by tomcat is not logged, any other error still is`(output: CapturedOutput) {
        val tomcat = LoggerFactory.getLogger("org.apache.catalina.core.ContainerBase.[Tomcat].[localhost].[/].[dispatcherServlet]")
        val ours = LoggerFactory.getLogger("com.dictara.gateway.controller.TranscribeController")

        tomcat.error("tomcat entry A", IOException("Broken pipe, marker-dropped"))
        tomcat.error("tomcat entry B", IllegalStateException("marker-kept-real-failure"))
        ours.error("our entry C", IOException("Broken pipe, marker-kept-ours"))

        assertThat(output.all).doesNotContain("marker-dropped")
        assertThat(output.all).contains("marker-kept-real-failure")
        assertThat(output.all).contains("marker-kept-ours")
    }
}
