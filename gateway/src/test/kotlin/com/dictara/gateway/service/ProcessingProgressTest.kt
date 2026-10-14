package com.dictara.gateway.service

import com.dictara.gateway.dto.ProgressInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProcessingProgressTest {

    @Test
    fun `transcribing reports the percent of the audio done, rounded down`() {
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = 0.0, totalS = 100.0)))
            .isEqualTo("transcribing" to 0)
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = 41.9, totalS = 100.0)))
            .isEqualTo("transcribing" to 41)
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = 100.0, totalS = 100.0)))
            .isEqualTo("transcribing" to 100)
    }

    @Test
    fun `the percent stays within 0 to 100`() {
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = 130.0, totalS = 100.0)).second).isEqualTo(100)
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = -5.0, totalS = 100.0)).second).isEqualTo(0)
    }

    @Test
    fun `unknown or empty totals give a phase but no percent`() {
        assertThat(processingProgress("processing", ProgressInfo("transcribing"))).isEqualTo("transcribing" to null)
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = 5.0, totalS = 0.0)))
            .isEqualTo("transcribing" to null)
        assertThat(processingProgress("processing", ProgressInfo("transcribing", processedS = null, totalS = 100.0)))
            .isEqualTo("transcribing" to null)
    }

    @Test
    fun `diarizing is named but has no percent`() {
        assertThat(processingProgress("processing", ProgressInfo("diarizing", 100.0, 100.0, diarizeProgress = 0.3)))
            .isEqualTo("diarizing" to null)
    }

    @Test
    fun `summarizing is named by its status and has no percent`() {
        assertThat(processingProgress("summarizing", null)).isEqualTo("summarizing" to null)
        assertThat(processingProgress("summarizing", ProgressInfo("transcribing", 1.0, 2.0))).isEqualTo("summarizing" to null)
    }

    @Test
    fun `a processing job nobody reports on has neither`() {
        assertThat(processingProgress("processing", null)).isEqualTo(null to null)
    }

    @Test
    fun `other statuses have neither, whatever the orchestrator remembers`() {
        for (status in listOf("pending", "done", "failed")) {
            assertThat(processingProgress(status, ProgressInfo("transcribing", 50.0, 100.0))).describedAs(status).isEqualTo(null to null)
        }
    }
}
