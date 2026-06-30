package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ToolExecutorSkillTest {

    @Test
    fun `skill tool rejects skills with missing required tools`() {
        val root = createTempDirectory()
        val skillDir = root.resolve(".code-assistant/skills/bad").createDirectories()
        skillDir.resolve("SKILL.md").writeText(
            """
            ---
            name: bad
            description: Bad skill
            command: bad
            tools:
              - NoSuchTool
            ---
            This should not run.
            """.trimIndent()
        )
        val session = AgentSession().apply {
            approvedTools.add("Skill")
            firstToolUseDone.add("Skill")
        }

        val result = ToolExecutor(projectAt(root.toString()), session)
            .execute(tool("Skill", mapOf("skill" to "bad")))

        assertTrue(result.startsWith("错误:"), result)
        assertContains(result, "工具缺失")
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
