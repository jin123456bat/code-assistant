package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolApprovalPolicyTest {

    @Test
    fun `requires approval for mutating and shell tools`() {
        assertTrue(ToolApprovalPolicy.requiresApproval("writeFile"))
        assertTrue(ToolApprovalPolicy.requiresApproval("editFile"))
        assertTrue(ToolApprovalPolicy.requiresApproval("runShell"))
        assertTrue(ToolApprovalPolicy.requiresApproval("spawnAgent"))
    }

    @Test
    fun `does not require approval for read-only tools`() {
        assertFalse(ToolApprovalPolicy.requiresApproval("readFile"))
        assertFalse(ToolApprovalPolicy.requiresApproval("listFiles"))
        assertFalse(ToolApprovalPolicy.requiresApproval("searchContent"))
        assertFalse(ToolApprovalPolicy.requiresApproval("readLints"))
    }

    @Test
    fun `describes command and file targets for approval dialog`() {
        val shellText = ToolApprovalPolicy.describe(
            "runShell",
            mapOf("command" to "./gradlew test", "workDir" to "/tmp/project")
        )
        assertContains(shellText, "./gradlew test")
        assertContains(shellText, "/tmp/project")

        val writeText = ToolApprovalPolicy.describe(
            "writeFile",
            mapOf("filePath" to "src/App.kt")
        )
        assertContains(writeText, "src/App.kt")
    }
}
