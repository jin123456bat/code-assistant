package com.aiassistant.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolApprovalPolicyTest {

    @Test
    fun `describes command and file targets for approval dialog`() {
        val shellText = ToolApprovalPolicy.describe(
            "Bash",
            mapOf("command" to "./gradlew test", "workDir" to "/tmp/project")
        )
        assertContains(shellText, "./gradlew test")
        assertContains(shellText, "/tmp/project")

        val writeText = ToolApprovalPolicy.describe(
            "Write",
            mapOf("filePath" to "src/App.kt")
        )
        assertContains(writeText, "src/App.kt")
    }

    @Test
    fun `isDangerousReason returns true for dangerous reasons`() {
        assertTrue(ToolApprovalPolicy.isDangerousReason(ToolApprovalPolicy.ApprovalReason.DANGEROUS_SHELL_COMMAND))
        assertTrue(ToolApprovalPolicy.isDangerousReason(ToolApprovalPolicy.ApprovalReason.DANGEROUS_FLAG))
        assertFalse(ToolApprovalPolicy.isDangerousReason(ToolApprovalPolicy.ApprovalReason.PUBLIC_API_CHANGE))
        assertFalse(ToolApprovalPolicy.isDangerousReason(ToolApprovalPolicy.ApprovalReason.FIRST_USE))
        assertFalse(ToolApprovalPolicy.isDangerousReason(null))
    }

    @Test
    fun `mcp first use is tracked by server not individual tool`() {
        val session = AgentSession().apply {
            firstToolUseDone.add("mcp:github")
        }

        val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(
            ToolApprovalPolicy.ApprovalContext(
                session = session,
                toolName = "github/list_issues",
                toolUse = tool("github/list_issues"),
                project = project()
            )
        )

        assertFalse(needsApproval)
        assertEquals(null, reason)
    }

    @Test
    fun `mcp approved server skips approval for all tools on that server`() {
        val session = AgentSession().apply {
            approvedMcpServers.add("github")
        }

        val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(
            ToolApprovalPolicy.ApprovalContext(
                session = session,
                toolName = "github/search",
                toolUse = tool("github/search"),
                project = project()
            )
        )

        assertFalse(needsApproval)
        assertEquals(null, reason)
    }

    private fun tool(name: String): BetaToolUseBlock =
        BetaToolUseBlock.builder()
            .id("tool-${System.nanoTime()}")
            .name(name)
            .input(JsonValue.from(emptyMap<String, Any>()))
            .build()

    private fun project(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> "/tmp/project"
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject"
                else -> null
            }
        } as Project
}
