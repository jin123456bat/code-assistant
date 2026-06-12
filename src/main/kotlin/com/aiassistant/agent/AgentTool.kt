package com.aiassistant.agent

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

}
