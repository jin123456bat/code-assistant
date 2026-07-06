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
    fun `mcp first use is tracked by server but does not approve server`() {
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

        assertTrue(needsApproval)
        assertEquals(null, reason)
    }

    @Test
    fun `fifth modified file in a turn triggers large scale approval`() {
        val session = AgentSession().apply {
            firstToolUseDone.add("Write")
            approvedTools.add("Write")
            filesModifiedThisTurn.addAll(listOf("A.kt", "B.kt", "C.kt", "D.kt"))
        }

        val (needsApproval, reason) = ToolApprovalPolicy.needsUserApproval(
            ToolApprovalPolicy.ApprovalContext(
                session = session,
                toolName = "Write",
                toolUse = tool("Write", mapOf("filePath" to "E.kt")),
                project = project()
            )
        )

        assertTrue(needsApproval)
        assertEquals(ToolApprovalPolicy.ApprovalReason.LARGE_SCALE_MODIFICATION, reason)
    }

    @Test
    fun `method body edit is not a method signature change`() {
        assertFalse(
            ToolApprovalPolicy.inputChangesMethodSignature(
                "Edit",
                mapOf(
                    "oldString" to "fun total(): Int { return 1 }",
                    "newString" to "fun total(): Int { return 2 }"
                )
            )
        )
    }

    @Test
    fun `method parameter edit is a method signature change`() {
        assertTrue(
            ToolApprovalPolicy.inputChangesMethodSignature(
                "Edit",
                mapOf(
                    "oldString" to "fun total(): Int { return 1 }",
                    "newString" to "fun total(count: Int): Int { return count }"
                )
            )
        )
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
        tool(name, emptyMap())

    private fun tool(name: String, input: Map<String, Any?>): BetaToolUseBlock =
        BetaToolUseBlock.builder()
            .id("tool-${System.nanoTime()}")
            .name(name)
            .input(JsonValue.from(input))
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
