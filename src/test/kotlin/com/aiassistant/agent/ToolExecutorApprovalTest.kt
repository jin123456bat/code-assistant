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
    fun `lowercase bash is canonicalized before approval and execution`() {
        val session = AgentSession()
        val executor = ToolExecutor(projectAt(createTempDirectory().toString()), session)
        var requested: ToolApprovalRequest? = null
        executor.onApprovalRequested = { request ->
            requested = request
            request.complete(ToolApprovalPolicy.ApprovalResult.REJECTED)
        }

        val result = executor.execute(
            tool(
                "bash",
                mapOf("command" to "sudo rm -rf /", "dangerous" to false)
            )
        )

        assertEquals("Bash", requested?.toolName)
        assertEquals(true, requested?.dangerous)
        assertEquals("用户拒绝执行工具: Bash", result)
        assertTrue("bash" !in session.firstToolUseDone)
    }

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

        val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(
            ToolApprovalPolicy.ApprovalContext(
                session = session,
                toolName = "Read",
                toolUse = tool("Read", mapOf("filePath" to "README.md")),
                project = projectAt(root.toString())
            )
        )
        assertEquals(true, needsApproval)
        assertEquals(null, reason)
    }

    @Test
    fun `mcp allow once marks server first use without approving the whole server`() {
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
            assertEquals(false, "github" in session.approvedMcpServers)
            assertEquals(true, needsApproval)
            assertEquals(null, reason)
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
