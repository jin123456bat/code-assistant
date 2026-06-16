package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.mcp.McpManager
import com.intellij.openapi.project.Project

/**
 * MCP Prompts 获取工具 — 对齐 Claude Code。
 * LLM 可调用此工具按需获取 MCP 服务器的 prompt 模板渲染内容。
 */
class McpGetPromptTool : AgentTool {
    override val name = "mcp_get_prompt"
    override val description = "获取 MCP 服务器提供的 prompt 模板渲染内容。参数 name 为 prompt 名称（见系统提示中的 ## MCP Prompts 列表），arguments 为可选的 JSON 参数对象（如 {\"key\":\"value\"}）。"
    override val parameters = listOf(
        ToolParameter("name", "string", "prompt 名称，来自系统提示中的 MCP Prompts 列表", required = true),
        ToolParameter("arguments", "string", "可选的 JSON 参数字符串，如 {\"path\":\"/src/main\"}")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("name 不能为空")
        val mcpManager = McpManager.getInstance(project.basePath) ?: return ToolResult.err("MCP 管理器未初始化")

        val args = try {
            params["arguments"]?.takeIf { it.isNotBlank() }?.let {
                val gson = com.google.gson.Gson()
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(it, Map::class.java) as? Map<String, String> ?: emptyMap()
            } ?: emptyMap()
        } catch (e: Exception) {
            return ToolResult.err("arguments 格式错误，请提供有效的 JSON 字符串: ${e.message}")
        }

        val content = mcpManager.getPrompt(name, args)
        return if (content != null && content.isNotBlank()) {
            ToolResult.ok(content)
        } else {
            ToolResult.err("MCP prompt 不存在或渲染失败: $name")
        }
    }
}
