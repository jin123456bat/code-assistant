package com.aiassistant.agent

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ToolExecutorMcpTest {

    @Test
    fun `mcp result is truncated to first 200 lines`() {
        val executor = ToolExecutor(projectAt(createTempDirectory().toString()), AgentSession())
        val lines = (1..205).joinToString("\n") { "line-$it" }

        val result = executor.truncateMcpResultForTest(lines)

        assertEquals(201, result.lines().size)
        assertContains(result, "line-1")
        assertContains(result, "line-200")
        assertContains(result, "... (共 205 行，已截断到 200 行)")
    }

    private fun ToolExecutor.truncateMcpResultForTest(text: String): String {
        val method =
            ToolExecutor::class.java.getDeclaredMethod("truncateMcpResult", String::class.java)
        method.isAccessible = true
        return method.invoke(this, text) as String
    }

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
