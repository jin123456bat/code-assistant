package com.aiassistant.agent

import com.aiassistant.shared.JsonUtils
import com.intellij.openapi.project.Project

/**
 * Agent 工具参数定义
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null
)

/**
 * 工具执行结果
 */
data class ToolResult(
    val success: Boolean,
    val content: String,
    val error: String? = null
) {
    companion object {
        fun ok(content: String) = ToolResult(true, content)
        fun err(message: String) = ToolResult(false, "", message)
    }
}

/**
 * Agent 工具接口 — 所有内置工具和 MCP 工具都实现此接口
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: List<ToolParameter>

    /**
     * 执行工具，params key 为参数名，value 为 JSON 字符串值
     */
    fun execute(params: Map<String, String>, project: Project): ToolResult

    /**
     * 生成 OpenAI 兼容的 function JSON Schema。
     * @deprecated v3 使用 Anthropic input_schema 格式（由 ToolRegistryV3.buildToolsJson() 生成）。
     */
    fun toFunctionJson(): String {
        val propsJson = parameters.joinToString(",") { p ->
            buildString {
                append("\"${p.name}\":{")
                append("\"type\":\"${p.type}\",")
                append("\"description\":\"${JsonUtils.escapeJson(p.description)}\"")
                if (p.enum != null) {
                    append(",\"enum\":[${p.enum.joinToString(",") { "\"$it\"" }}]")
                }
                append("}")
            }
        }
        val requiredJson = parameters.filter { it.required }.joinToString(",") { "\"${it.name}\"" }
        val requiredBlock = if (requiredJson.isNotEmpty()) ",\"required\":[$requiredJson]" else ""

        return """{"name":"$name","description":"${JsonUtils.escapeJson(description)}","parameters":{"type":"object","properties":{$propsJson},"additionalProperties":false$requiredBlock}}"""
    }
}

/**
 * 工具注册中心 — 管理所有可用工具。
 *
 * @deprecated 已由 agent_v3/ToolRegistryV3 取代（Anthropic input_schema 格式 + 缓存）。
 *             此文件保留用于测试兼容性。
 */
@Deprecated("Use ToolRegistryV3 instead", replaceWith = ReplaceWith("ToolRegistryV3"))
class ToolRegistry {
    private val tools = mutableListOf<AgentTool>()

    fun register(tool: AgentTool) {
        tools.add(tool)
    }

    fun registerAll(newTools: List<AgentTool>) {
        tools.addAll(newTools)
    }

    fun getTools(): List<AgentTool> = tools.toList()

    fun findTool(name: String): AgentTool? = tools.find { it.name == name }

    /**
     * 生成 OpenAI 兼容的 tools 数组 JSON
     */
    fun buildToolsJson(): String {
        return tools.joinToString(",") { tool ->
            """{"type":"function","function":${tool.toFunctionJson()}}"""
        }.let { "[$it]" }
    }
}
