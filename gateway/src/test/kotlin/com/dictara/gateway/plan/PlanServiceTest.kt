package com.dictara.gateway.plan

import com.dictara.gateway.entity.PlanEntity
import com.dictara.gateway.repository.PlanRepository
import com.dictara.gateway.repository.SubmissionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

/** [PlanService.strategyFor] and the "no plan" fail-loud path — a pure Mockito unit test, no DB needed
 *  (the plan-type-specific behavior of each [PlanStrategy] itself is covered by its own class/integration
 *  tests). */
@ExtendWith(MockitoExtension::class)
class PlanServiceTest {

    @Mock lateinit var planRepo: PlanRepository
    @Mock lateinit var submissionRepo: SubmissionRepository

    private val mapper = ObjectMapper()
    private val userId = UUID.randomUUID()

    private fun service() = PlanService(planRepo, submissionRepo)

    private fun plan(name: String, constraints: Map<String, Any> = emptyMap()) =
        PlanEntity(name, name, mapper.valueToTree(constraints))

    @Test
    fun `a plan with a lifetime cap enforces and reports it, even if a monthly cap is also set`() {
        `when`(planRepo.findByUserId(userId)).thenReturn(plan("trial", mapOf("lifetime_submissions" to 2, "monthly_submissions" to 10)))
        `when`(submissionRepo.countAllSubmissions(userId)).thenReturn(2L)

        assertThatThrownBy { service().enforce(userId) }.hasMessageContaining("free trial")
        assertThat(service().info(userId).usage).isEqualTo(PlanUsage(used = 2, max = 2, unit = "transcriptions"))
    }

    @Test
    fun `a plan with only a monthly cap enforces and reports that instead`() {
        `when`(planRepo.findByUserId(userId)).thenReturn(plan("free", mapOf("monthly_submissions" to 10)))
        `when`(submissionRepo.countCurrentMonthSubmissions(userId)).thenReturn(3L)

        service().enforce(userId) // under the cap — does not throw
        assertThat(service().info(userId).usage).isEqualTo(PlanUsage(used = 3, max = 10, unit = "transcriptions"))
    }

    @Test
    fun `a plan with no constraints at all enforces nothing and reports no denominator`() {
        `when`(planRepo.findByUserId(userId)).thenReturn(plan("unlimited"))
        `when`(submissionRepo.countCurrentMonthSubmissions(userId)).thenReturn(99L)

        service().enforce(userId) // no cap — does not throw
        assertThat(service().info(userId).usage).isEqualTo(PlanUsage(used = 99, max = null, unit = "transcriptions"))
    }

    @Test
    fun `enforce fails loud instead of silently granting unlimited access for a user with no plan row`() {
        `when`(planRepo.findByUserId(userId)).thenReturn(null)

        assertThatThrownBy { service().enforce(userId) }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessageContaining(userId.toString())
    }

    @Test
    fun `info fails loud the same way, instead of quietly reporting a fake Free plan`() {
        `when`(planRepo.findByUserId(userId)).thenReturn(null)

        assertThatThrownBy { service().info(userId) }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessageContaining(userId.toString())
    }
}
