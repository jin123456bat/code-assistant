package com.aiassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolTest {

    @Test
    fun `should build tools json`() {
        val registry = ToolRegistry()
        registry.register(FakeTool("test_tool", "A test tool"))

        val json = registry.buildToolsJson()
        assertTrue(json.contains(""""type":"function""""))
        assertTrue(json.contains(""""name":"test_tool""""))
        assertTrue(json.contains(""""description":"A test tool""""))
    }

    @Test
    fun `should find tool by name`() {
        val registry = ToolRegistry()
        registry.register(FakeTool("find_me", "test"))
        assertNotNull(registry.findTool("find_me"))
        assertNull(registry.findTool("not_found"))
    }

    @Test
    fun `should build function json with required params`() {
        val tool = FakeTool("required_tool", "Has required params", listOf(
            ToolParameter("path", "string", "file path", required = true),
            ToolParameter("mode", "string", "mode", enum = listOf("read", "write"))
        ))
        val json = tool.toFunctionJson()
        assertTrue(json.contains(""""required":["path"]"""))
        assertTrue(json.contains(""""enum":["read","write"]"""))
        assertTrue(json.contains(""""additionalProperties":false"""))
    }

    @Test
    fun `should escape special chars in json`() {
        val tool = FakeTool("quote_tool", "Has \"quotes\" and newlines")
        val json = tool.toFunctionJson()
        assertTrue("should escape double quotes", json.contains("\\\""))
        assertTrue(json.contains("newlines"))
    }
}

private class FakeTool(
    override val name: String,
    override val description: String,
    override val parameters: List<ToolParameter> = emptyList()
) : AgentTool {
    override fun execute(params: Map<String, String>, project: com.intellij.openapi.project.Project) =
        com.aiassistant.agent.ToolResult.ok("ok")
}
