package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.aiassistant.mcp.McpToolStub
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolExecutorApprovalTest {

    @Test
    fun `approval request callback can allow tool execution without dialog`() {
        val root = createTempDirectory()
        root.resolve("README.md").writeText("hello approval")
        val session = AgentSession()
        val executor = ToolExecutor(projectAt(root.toString()), session)
        var requested: ToolApprovalRequest? = null
        executor.onApprovalRequested = { request ->
            requested = request
            request.complete(ToolApprovalPolicy.ApprovalResult.ALLOW_ONCE)
        }

        val result = executor.execute(tool("Read", mapOf("filePath" to "README.md")))

        assertEquals("Read", requested?.toolName)
        assertTrue(requested?.message?.contains("首次调用 Read 工具") == true)
        assertContains(result, "hello approval")
    }

    @Test
    fun `mcp allow once does not approve the whole server`() {
        ToolRegistry.register(
            "github/search",
            McpToolStub::class.java,
            ToolRegistry.ToolInfo("github/search", "Search", "")
        )
        try {
            val session = AgentSession()
            val executor = ToolExecutor(projectAt(createTempDirectory().toString()), session)
            executor.onApprovalRequested = { request ->
                request.complete(ToolApprovalPolicy.ApprovalResult.ALLOW_ONCE)
            }

            executor.execute(tool("github/search", emptyMap()))

            val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(
                ToolApprovalPolicy.ApprovalContext(
                    session = session,
                    toolName = "github/list_issues",
                    toolUse = tool("github/list_issues", emptyMap()),
                    project = projectAt(createTempDirectory().toString())
                )
            )
            assertTrue(needsApproval)
            assertEquals(ToolApprovalPolicy.ApprovalReason.FIRST_USE, reason)
        } finally {
            ToolRegistry.unregister("github/search")
        }
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
