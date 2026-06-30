package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ToolExecutorPlanTest {

    @Test
    fun `createPlan accepts Anthropic JsonValue plans input`() {
        val session = approvedSession("createPlan")
        val executor = ToolExecutor(projectAt(createTempDirectory().toString()), session)

        val result = executor.execute(
            tool(
                "createPlan",
                mapOf(
                    "task" to "修复 UI",
                    "plans" to listOf(
                        mapOf(
                            "description" to "读取 ChatPage",
                            "tool" to "Read",
                            "files" to listOf("src/main/kotlin/com/aiassistant/ui/page/ChatPage.kt")
                        )
                    )
                )
            )
        )

        assertContains(result, "计划已创建")
        assertEquals(1, session.plan?.plans?.size)
        assertEquals("读取 ChatPage", session.plan?.plans?.single()?.description)
    }

    @Test
    fun `reorderPlans accepts Anthropic JsonValue planIds input`() {
        val session = approvedSession("createPlan", "reorderPlans")
        val executor = ToolExecutor(projectAt(createTempDirectory().toString()), session)
        executor.execute(
            tool(
                "createPlan",
                mapOf(
                    "task" to "重排",
                    "plans" to listOf(
                        mapOf(
                            "description" to "第一项",
                            "tool" to "Read",
                            "files" to emptyList<String>()
                        ),
                        mapOf(
                            "description" to "第二项",
                            "tool" to "Read",
                            "files" to emptyList<String>()
                        )
                    )
                )
            )
        )
        val ids = session.plan!!.plans.map { it.id }

        val result = executor.execute(tool("reorderPlans", mapOf("planIds" to ids.reversed())))

        assertContains(result, "已重排计划项顺序")
        assertEquals(ids.reversed(), session.plan!!.plans.map { it.id })
    }

    private fun approvedSession(vararg tools: String): AgentSession =
        AgentSession().apply {
            approvedTools.addAll(tools)
            firstToolUseDone.addAll(tools)
        }

    private fun tool(name: String, input: Map<String, Any>): BetaToolUseBlock =
        BetaToolUseBlock.builder()
            .id("tool-${System.nanoTime()}")
            .name(name)
            .input(JsonValue.from(input))
            .build()

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
