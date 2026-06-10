package com.aiassistant.agent_v3

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.shared.JsonUtils
import com.aiassistant.tools.*
import com.intellij.openapi.project.Project

/**
 * v3 统一工具注册中心 — Anthropic input_schema 格式。
 */
class ToolRegistryV3 {

    private val tools = mutableMapOf<String, AgentTool>()
    private val mcpTools = mutableMapOf<String, AgentTool>()
    private val skillTools = mutableMapOf<String, AgentTool>()
    @Volatile private var cachedJson: String? = null

    fun registerBuiltIn() {
        listOf(
            ReadFileTool(), WriteFileTool(), SearchCodeTool(), ListDirectoryTool(),
            ExecuteCommandTool(), GitDiffTool(), GitLogTool(), GitStatusTool(),
            AskUserTool()
        ).forEach { tools[it.name] = it }
        invalidateCache()
    }

    fun registerMcp(mcp: List<AgentTool>) { mcp.forEach { mcpTools[it.name] = it }; invalidateCache() }
    fun registerSkills(skills: List<AgentTool>) { skills.forEach { skillTools[it.name] = it }; invalidateCache() }

    fun getAll(): List<AgentTool> = (tools.values + mcpTools.values + skillTools.values).toList()
    fun find(name: String): AgentTool? = tools[name] ?: mcpTools[name] ?: skillTools[name]

    fun executeTool(name: String, params: Map<String, String>, project: Project): ToolResult =
        find(name)?.execute(params, project) ?: ToolResult.err("未知工具: $name")

    /** 生成 Anthropic input_schema 格式的工具列表（带缓存） */
    fun buildToolsJson(): String {
        cachedJson?.let { return it }
        val json = getAll().joinToString(",") { tool ->
            val schema = buildInputSchema(tool)
            """{"name":"${tool.name}","description":"${JsonUtils.escapeJson(tool.description)}","input_schema":$schema}"""
        }.let { "[$it]" }
        cachedJson = json
        return json
    }

    private fun invalidateCache() { cachedJson = null }

    private fun buildInputSchema(tool: AgentTool): String {
        val propsJson = tool.parameters.joinToString(",") { p ->
            buildString {
                append("\"${p.name}\":{\"type\":\"${p.type}\",\"description\":\"${JsonUtils.escapeJson(p.description)}\"")
                if (p.enum != null) append(",\"enum\":[${p.enum.joinToString(",") { "\"$it\"" }}]")
                append("}")
            }
        }
        val requiredJson = tool.parameters.filter { it.required }.joinToString(",") { "\"${it.name}\"" }
        val requiredBlock = if (requiredJson.isNotEmpty()) ",\"required\":[$requiredJson]" else ""
        return """{"type":"object","properties":{$propsJson},"additionalProperties":false$requiredBlock}"""
    }
}
