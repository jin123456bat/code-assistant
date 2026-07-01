package com.aiassistant.agent

import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.core.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ToolRegistryTest {

    @Test
    fun `内置工具注册后可通过名称查找`() {
        assertNotNull(ToolRegistry.get("Read"), "Read 工具应已注册")
        assertNotNull(ToolRegistry.get("Write"), "Write 工具应已注册")
        assertNotNull(ToolRegistry.get("Edit"), "Edit 工具应已注册")
        assertNotNull(ToolRegistry.get("Bash"), "Bash 工具应已注册")
        assertNotNull(ToolRegistry.get("Glob"), "Glob 工具应已注册")
        assertNotNull(ToolRegistry.get("Grep"), "Grep 工具应已注册")
        assertNotNull(ToolRegistry.get("readLints"), "readLints 工具应已注册")
        assertNotNull(ToolRegistry.get("Agent"), "Agent 工具应已注册")
    }

    @Test
    fun `未注册工具查找返回 null`() {
        assertNull(ToolRegistry.get("NonExistentTool"))
    }

    @Test
    fun `注册工具后可通过 get 查找`() {
        ToolRegistry.register(
            "test-tool",
            TestTool::class.java,
            ToolRegistry.ToolInfo("test-tool", "测试工具", "用于单元测试")
        )
        assertNotNull(ToolRegistry.get("test-tool"))
        ToolRegistry.unregister("test-tool")
    }

    @Test
    fun `注销后查找返回 null`() {
        ToolRegistry.register(
            "temp-tool",
            TestTool::class.java,
            ToolRegistry.ToolInfo("temp-tool", "临时工具", "")
        )
        ToolRegistry.unregister("temp-tool")
        assertNull(ToolRegistry.get("temp-tool"))
    }

    @Test
    fun `listAll 返回所有注册工具的类列表`() {
        val all = ToolRegistry.listAll()
        assertTrue(all.isNotEmpty(), "应至少包含内置工具")
        assertTrue(all.contains(Read::class.java))
        assertTrue(all.contains(Write::class.java))
    }

    @Test
    fun `listNames 返回所有工具名称`() {
        val names = ToolRegistry.listNames()
        assertTrue(names.contains("Read"))
        assertTrue(names.contains("Write"))
        assertTrue(names.contains("Edit"))
        assertTrue(names.contains("Bash"))
        assertTrue(names.contains("Glob"))
        assertTrue(names.contains("Grep"))
        assertTrue(names.contains("readLints"))
        assertTrue(names.contains("Agent"))
    }

    @Test
    fun `listBuiltin 不包含 MCP 工具`() {
        ToolRegistry.register(
            "docs/search",
            TestTool::class.java,
            ToolRegistry.ToolInfo(
                "docs/search",
                "MCP 测试",
                "",
                betaTool = mcpBetaTool("docs/search")
            )
        )
        val builtins = ToolRegistry.listBuiltin()
        assertFalse(builtins.contains("docs/search"), "builtin 列表不应包含 MCP 工具")
        ToolRegistry.unregister("docs/search")
    }

    @Test
    fun `listMcp 仅返回 MCP 工具`() {
        ToolRegistry.register(
            "docs/search",
            TestTool::class.java,
            ToolRegistry.ToolInfo(
                "docs/search",
                "MCP 测试",
                "",
                betaTool = mcpBetaTool("docs/search")
            )
        )
        val mcps = ToolRegistry.listMcp()
        assertTrue(mcps.contains(TestTool::class.java))
        ToolRegistry.unregister("docs/search")
    }

    @Test
    fun `buildSystemPromptTools 返回非空字符串`() {
        val prompt = ToolRegistry.buildSystemPromptTools()
        assertTrue(prompt.isNotEmpty(), "system prompt 工具指南不应为空")
        assertTrue(prompt.contains("工具使用指南"), "应包含标题")
    }

    @Test
    fun `buildSystemPromptTools 包含并行调用提示`() {
        val prompt = ToolRegistry.buildSystemPromptTools()
        assertTrue(prompt.contains("并行调用"), "应包含并行调用提示")
    }

    @Test
    fun `toToolDefinitions 返回工具名称列表`() {
        val defs = ToolRegistry.toToolDefinitions()
        assertTrue(defs.contains("Read"))
        assertTrue(defs.contains("Write"))
    }

    private fun mcpBetaTool(name: String): BetaTool =
        BetaTool.builder()
            .name(name)
            .description("MCP 测试")
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .type(JsonValue.from("object"))
                    .build()
            )
            .build()

    // 测试辅助类
    private class TestTool
}
