package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertContains
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
}
