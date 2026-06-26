package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolApprovalPolicyTest {

    @Test
    fun `requires approval for mutating and shell tools`() {
        assertTrue(ToolApprovalPolicy.requiresApproval("Write"))
        assertTrue(ToolApprovalPolicy.requiresApproval("Edit"))
        assertTrue(ToolApprovalPolicy.requiresApproval("Bash"))
        assertTrue(ToolApprovalPolicy.requiresApproval("Task"))
    }

    @Test
    fun `does not require approval for read-only tools`() {
        assertFalse(ToolApprovalPolicy.requiresApproval("Read"))
        assertFalse(ToolApprovalPolicy.requiresApproval("Glob"))
        assertFalse(ToolApprovalPolicy.requiresApproval("Grep"))
        assertFalse(ToolApprovalPolicy.requiresApproval("readLints"))
    }

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
}
