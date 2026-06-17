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

    /** 注册内置工具。allowedTools 为 null 时注册全部，非 null 时仅注册白名单中的工具。deniedTools 从白名单中排除 */
    fun registerBuiltIn(allowedTools: Set<String>? = null, deniedTools: Set<String> = emptySet()) {
        val all = listOf(
            ReadFileTool(), WriteFileTool(), SearchCodeTool(), ListDirectoryTool(),
            ExecuteCommandTool(), GitDiffTool(), GitLogTool(), GitStatusTool(),
            AskUserTool(), WebSearchTool(), WebFetchTool(), NotebookEditTool(), TaskTool(),
            CodeIntelligenceTool(), McpGetPromptTool()
        )
        all.forEach {
            val allowed = allowedTools == null || it.name in allowedTools
            val denied = it.name in deniedTools
            if (allowed && !denied) {
                tools[it.name] = it
            }
        }
    }

    fun registerMcp(mcp: List<AgentTool>) { mcp.forEach { mcpTools[it.name] = it } }
    fun clearMcp() { mcpTools.clear() }
    /** 原子替换 MCP 工具，消除 clearMcp/registerMcp 之间的 TOCTOU 窗口 */
    fun replaceMcp(mcp: List<AgentTool>) {
        synchronized(mcpTools) {
            mcpTools.clear()
            mcp.forEach { mcpTools[it.name] = it }
        }
    }

    fun getAll(): List<AgentTool> = synchronized(mcpTools) { (tools.values + mcpTools.values).toList() }
    // 查找优先级：内置工具 > MCP
    fun find(name: String): AgentTool? = tools[name] ?: mcpTools[name]

    fun executeTool(name: String, params: Map<String, String>, project: Project,
                    onProgress: ((String) -> Unit)? = null): ToolResult =
        find(name)?.execute(params, project, onProgress) ?: ToolResult.err("未知工具: $name")
}
