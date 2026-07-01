package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun `write rejects when another agent holds file lock`() {
        val root = createTempDirectory()
        val projectDir = root.resolve("project").toFile().apply { mkdirs() }
        val project = projectAt(projectDir.absolutePath)
        val file = projectDir.resolve("locked.txt")
        val lock = MultiAgentManager(project).acquireFileLock(file.canonicalPath)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread {
            lock.lock()
            try {
                locked.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally {
                lock.unlock()
            }
        }
        holder.start()
        assertTrue(locked.await(5, TimeUnit.SECONDS))

        val result = ToolExecutor(project, approvedSession("Write"))
            .execute(tool("Write", mapOf("filePath" to "locked.txt", "content" to "new")))

        release.countDown()
        holder.join(5000)
        assertContains(result, "正在被其他 Agent 修改")
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
