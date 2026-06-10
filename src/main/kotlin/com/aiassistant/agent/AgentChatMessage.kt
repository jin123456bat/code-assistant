package com.aiassistant.agent

import com.aiassistant.shared.JsonUtils

/**
 * 增强的聊天消息类型，支持 Agent 工具调用。
 *
 * @deprecated 已由 agent_v3/AgentMessage + AnthropicAdapter 取代。
 *             此文件仅保留用于测试兼容性。
 */
data class AgentChatMessage(
    val role: String,           // "user" | "assistant" | "system" | "tool"
    val content: String,
    val toolCalls: List<ToolCallData>? = null,   // assistant 发出工具调用
    val toolCallId: String? = null,               // tool 结果回传
    val toolName: String? = null                  // tool 结果显示用
)

/**
 * 工具调用数据 — 从 API 响应解析
 */
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String  // JSON string of parameters
)

/**
 * 将 AgentChatMessage 转为 API 所需的 messages JSON 数组元素
 */
fun AgentChatMessage.toApiJson(): String {
    return when {
        role == "tool" -> {
            """{"role":"tool","tool_call_id":"${toolCallId ?: ""}","content":"${JsonUtils.escapeJson(content)}"}"""
        }
        toolCalls != null && toolCalls.isNotEmpty() -> {
            val callsJson = toolCalls.joinToString(",") { tc ->
                """{"id":"${tc.id}","type":"function","function":{"name":"${tc.name}","arguments":"${JsonUtils.escapeJson(tc.arguments)}"}}"""
            }
            """{"role":"assistant","content":null,"tool_calls":[$callsJson]}"""
        }
        role == "assistant" -> {
            """{"role":"assistant","content":"${JsonUtils.escapeJson(content)}"}"""
        }
        else -> {
            """{"role":"${JsonUtils.escapeJson(role)}","content":"${JsonUtils.escapeJson(content)}"}"""
        }
    }
}
