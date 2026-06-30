package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ToolExecutorPathTest {

    @Test
    fun `read rejects paths escaping project root`() {
        val root = createTempDirectory()
        val projectDir = root.resolve("project").toFile().apply { mkdirs() }
        root.resolve("secret.txt").writeText("outside")
        val session = approvedSession("Read")

        val result = ToolExecutor(projectAt(projectDir.absolutePath), session)
            .execute(tool("Read", mapOf("filePath" to "../secret.txt")))

        assertTrue(result.startsWith("错误:"), result)
        assertContains(result, "项目根目录")
    }

    @Test
    fun `read records read state without marking file modified`() {
        val root = createTempDirectory()
        val projectDir = root.resolve("project").toFile().apply { mkdirs() }
        root.resolve("project/README.md").writeText("hello")
        val session = approvedSession("Read")

        val result = ToolExecutor(projectAt(projectDir.absolutePath), session)
            .execute(tool("Read", mapOf("filePath" to "README.md")))

        assertTrue(result.contains("[文件: README.md"), result)
        assertTrue("README.md" in session.filesReadThisTurn)
        assertTrue(session.filesModifiedThisTurn.isEmpty())
    }

    private fun approvedSession(toolName: String): AgentSession =
        AgentSession().apply {
            approvedTools.add(toolName)
            firstToolUseDone.add(toolName)
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
