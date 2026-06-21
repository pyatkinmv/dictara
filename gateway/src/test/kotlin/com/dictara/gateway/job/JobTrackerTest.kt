package com.dictara.gateway.job

import com.dictara.gateway.entity.JobRunEntity
import com.dictara.gateway.repository.JobRunRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class JobTrackerTest {

    @Mock lateinit var jobRunRepo: JobRunRepository

    lateinit var jobTracker: JobTracker

    private val persistedRun = JobRunEntity(jobName = "test-job", id = UUID.randomUUID())

    @BeforeEach
    fun setup() {
        jobTracker = JobTracker(jobRunRepo)
        `when`(jobRunRepo.save(any())).thenReturn(persistedRun)
    }

    @Test
    fun `saves running record on start, then completed with rows_affected on success`() {
        jobTracker.tracked("test-job") { 7 }

        val captor = ArgumentCaptor.forClass(JobRunEntity::class.java)
        verify(jobRunRepo, times(2)).save(captor.capture())
        val (initial, final) = captor.allValues
        assertThat(initial.status).isEqualTo("running")
        assertThat(initial.finishedAt).isNull()
        assertThat(final.status).isEqualTo("completed")
        assertThat(final.rowsAffected).isEqualTo(7)
        assertThat(final.finishedAt).isNotNull()
    }

    @Test
    fun `saves failed record and rethrows exception`() {
        assertThatThrownBy { jobTracker.tracked("test-job") { throw RuntimeException("boom") } }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("boom")

        val captor = ArgumentCaptor.forClass(JobRunEntity::class.java)
        verify(jobRunRepo, times(2)).save(captor.capture())
        val (initial, final) = captor.allValues
        assertThat(initial.status).isEqualTo("running")
        assertThat(final.status).isEqualTo("failed")
        assertThat(final.error).isEqualTo("boom")
        assertThat(final.finishedAt).isNotNull()
    }

    @Test
    fun `rows_affected is null when block returns non-Int`() {
        jobTracker.tracked("test-job") { "some-string" }

        val captor = ArgumentCaptor.forClass(JobRunEntity::class.java)
        verify(jobRunRepo, times(2)).save(captor.capture())
        assertThat(captor.allValues[1].rowsAffected).isNull()
    }

    @Test
    fun `returns value from block`() {
        val result = jobTracker.tracked("test-job") { 42 }
        assertThat(result).isEqualTo(42)
    }
}
