package com.aiassistant.agent

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolResult
import com.aiassistant.tools.*
import com.intellij.openapi.project.Project

/**
 * v3 统一工具注册中心。
 */
class ToolRegistryV3 {

    private val tools = mutableMapOf<String, AgentTool>()
    private val mcpTools = java.util.concurrent.ConcurrentHashMap<String, AgentTool>()

    fun registerBuiltIn() {
        listOf(
            ReadFileTool(), WriteFileTool(), SearchCodeTool(), ListDirectoryTool(),
            ExecuteCommandTool(), GitDiffTool(), GitLogTool(), GitStatusTool(),
            AskUserTool(), WebSearchTool(), WebFetchTool(), NotebookEditTool(), TaskTool(),
            CodeIntelligenceTool(), McpGetPromptTool()
        ).forEach { tools[it.name] = it }
    }

    fun registerMcp(mcp: List<AgentTool>) { mcp.forEach { mcpTools[it.name] = it } }
    fun clearMcp() { mcpTools.clear() }

    fun getAll(): List<AgentTool> = (tools.values + mcpTools.values).toList()
    // 查找优先级：内置工具 > MCP
    fun find(name: String): AgentTool? = tools[name] ?: mcpTools[name]

    fun executeTool(name: String, params: Map<String, String>, project: Project): ToolResult =
        find(name)?.execute(params, project) ?: ToolResult.err("未知工具: $name")
}
