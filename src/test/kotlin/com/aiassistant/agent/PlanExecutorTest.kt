package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlanExecutorTest {

    @Test
    fun `executor uses existing session plan`() {
        val session = AgentSession()
        session.plan = PlanExecutor.Plan(
            summary = "existing",
            plans = mutableListOf(
                PlanExecutor.PlanItem(description = "read file", tool = "Read", files = emptyList())
            )
        )

        val executor = PlanExecutor(session)

        assertNotNull(executor.currentPlan)
        assertEquals(1, executor.listPlans().size)
    }

    @Test
    fun `executeNext runs next paused item and advances plan`() {
        val session = AgentSession()
        val executor = PlanExecutor(session)
        val plan = executor.createPlanFromTool(
            "ship",
            listOf(
                mapOf("description" to "first", "tool" to "Read", "files" to emptyList<String>()),
                mapOf("description" to "second", "tool" to "Edit", "files" to emptyList<String>())
            )
        )

        val result = executor.executeNext { step -> "done ${step.description}" }

        assertEquals(plan.plans.first().id, result?.planId)
        assertEquals(PlanExecutor.PlanItem.ItemStatus.COMPLETED, plan.plans[0].status)
        assertEquals("done first", plan.plans[0].result)
        assertEquals(1, plan.currentPlanIndex)
        assertEquals(PlanExecutor.Plan.Status.EXECUTING, plan.status)
    }
}
