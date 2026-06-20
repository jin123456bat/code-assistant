package com.aiassistant.hooks

import org.junit.Test
import org.junit.Assert.*

class PromptHookRunnerTest {
    @Test fun `run expands TOOL_NAME variable`() {
        val context = HookEventContext("PreToolUse", tool_name = "write_file", session_id = "abc", project_dir = "/proj")
        val result = PromptHookRunner.run("工具 \$TOOL_NAME 已执行", context)
        assertNotNull(result.content)
        assertTrue(result.content!!.contains("write_file"))
    }

    @Test fun `run expands PROJECT_DIR variable`() {
        val context = HookEventContext("PreToolUse", tool_name = "test", session_id = "abc", project_dir = "/home/project")
        val result = PromptHookRunner.run("项目目录: \$PROJECT_DIR", context)
        assertTrue(result.content!!.contains("/home/project"))
    }

    @Test fun `run handles missing variables gracefully`() {
        val context = HookEventContext("PreToolUse", tool_name = null, session_id = "abc", project_dir = null)
        val result = PromptHookRunner.run("tool=\$TOOL_NAME dir=\$PROJECT_DIR", context)
        assertEquals("tool= dir=", result.content)
    }

    @Test fun `run returns prompt as-is when no variables`() {
        val context = HookEventContext("SessionStart", session_id = "abc")
        val result = PromptHookRunner.run("hello world", context)
        assertEquals("hello world", result.content)
    }
}
