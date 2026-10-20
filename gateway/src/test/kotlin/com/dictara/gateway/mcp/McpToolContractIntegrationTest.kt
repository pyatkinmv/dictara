package com.dictara.gateway.mcp

import com.dictara.gateway.guard.CallOrigin
import com.dictara.gateway.guard.PaidOperationNotAllowedException
import com.dictara.gateway.mcp.token.McpAccess
import com.dictara.gateway.mcp.tools.ToolContext
import com.dictara.gateway.mcp.tools.ToolRegistry
import com.dictara.gateway.port.SummarizerPort
import com.dictara.gateway.port.TranscriberPort
import com.dictara.gateway.service.BacklogReanalysisService
import com.dictara.gateway.service.SubmissionService
import com.dictara.gateway.service.SummaryRegenerationService
import com.dictara.gateway.storage.AudioRef
import com.dictara.gateway.storage.UploadResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.mock.mockito.MockBean
import java.util.UUID

private fun <T> anything(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * The promise that MCP never starts paid work, tested over the tools themselves: whatever tools exist (the registry is
 * walked, so a tool added later is covered without anyone remembering to add a test), each is called as MCP with
 * arguments of every kind, and not one reaches the summarizer, the transcriber or storage writes.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class McpToolContractIntegrationTest : AbstractMcpIntegrationTest() {

    @MockBean lateinit var summarizer: SummarizerPort
    @MockBean lateinit var transcriber: TranscriberPort

    @Autowired lateinit var registry: ToolRegistry
    @Autowired lateinit var regeneration: SummaryRegenerationService
    @Autowired lateinit var backlog: BacklogReanalysisService
    @Autowired lateinit var submissionService: SubmissionService

    private val argumentSets = listOf(
        "{}", """{"id":"not-an-id"}""", """{"query":"x","tags":["a"],"speakers":["b"],"status":["done"],"size":5}""",
        """{"from_s":0,"to_s":10,"offset":0,"limit":5}""",
    )

    @Test
    fun `no tool, called as MCP with any arguments, reaches the summarizer, the transcriber or a storage write`() {
        val user = newUser()
        val submission = seed(user, "Contract", listOf(Phrase(0.0, 5.0, "SPEAKER_00", "Hello.")))
        val token = mint(user, McpAccess.WRITE)
        assertThat(registry.all).isNotEmpty()

        for (tool in registry.all) {
            val sets = argumentSets + """{"id":"$submission"}"""
            for (args in sets) {
                // Through the real endpoint, as an agent would, and straight at the tool as MCP.
                call(token, tool.name, args)
                runCatching {
                    CallOrigin.runAs(CallOrigin.MCP) { tool.call(ToolContext(user, "contract", McpAccess.WRITE), json.readTree(args)) }
                }.onFailure { assertThat(it).`as`("${tool.name} $args").isNotInstanceOf(PaidOperationNotAllowedException::class.java) }
            }
        }

        // Asking whether a summarizer is configured, or how a job stands, is free; what costs money is calling it.
        verify(summarizer, never()).analyze(anything())
        verify(transcriber, never()).submitByReference(anything(), anything())
        verify(audioStorage, never()).upload(anything(), anything(), anything(), ArgumentMatchers.anyLong(), anything())
        verify(audioStorage, never()).uploadBytes(anything(), anything())
    }

    @Test
    fun `the entry points that start paid work refuse a call that came from MCP`() {
        val id = UUID.randomUUID()

        assertThatThrownBy { CallOrigin.runAs(CallOrigin.MCP) { regeneration.start(UUID.randomUUID(), id) } }
            .isInstanceOf(PaidOperationNotAllowedException::class.java)
        assertThatThrownBy { CallOrigin.runAs(CallOrigin.MCP) { backlog.start(UUID.randomUUID(), listOf(id.toString()), false) } }
            .isInstanceOf(PaidOperationNotAllowedException::class.java)
        assertThatThrownBy {
            CallOrigin.runAs(CallOrigin.MCP) {
                submissionService.createSubmission(
                    UUID.randomUUID(), UUID.randomUUID(), "a.m4a", "audio/mp4", 1, UploadResult(AudioRef("gs://x/y"), ""),
                    model = "fast", languageHint = "auto", diarize = false, numSpeakers = null, summaryMode = "off",
                    source = "web", telegramChatId = null, telegramMessageId = null,
                )
            }
        }.isInstanceOf(PaidOperationNotAllowedException::class.java)
        verifyNoInteractions(summarizer, transcriber)
    }

}
