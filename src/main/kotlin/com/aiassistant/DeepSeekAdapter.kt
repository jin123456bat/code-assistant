package com.aiassistant

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolCallData
import com.aiassistant.shared.JsonUtils

/**
 * DeepSeek API adapter. Builds chat completion requests
 * and parses responses into message content or tool calls.
 *
 * @deprecated 已由 AnthropicAdapter 取代。v3 Agent 循环使用 Anthropic Messages API 格式。
 *             此文件仅保留用于测试兼容性，不应在新代码中使用。
 */
class DeepSeekAdapter(
    val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL
) {

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEFAULT_MODEL = "deepseek-chat"
    }

    fun buildRequest(messages: List<ChatMessage>, stream: Boolean = true, toolsJson: String? = null): String {
        val messagesJson = messages.joinToString(",") { msg ->
            """{"role":"${JsonUtils.escapeJson(msg.role)}","content":"${JsonUtils.escapeJson(msg.content)}"}"""
        }
        val toolsBlock = if (!toolsJson.isNullOrBlank()) ""","tools":$toolsJson""" else ""
        return """{"model":"$model","messages":[$messagesJson],"stream":$stream$toolsBlock}"""
    }

    fun parseDeltaContent(eventData: String): String? {
        return try {
            val contentKey = "\"content\":\""
            val contentIdx = eventData.indexOf(contentKey)
            if (contentIdx == -1) return null

            // content 可能为 null（tool_calls 场景）
            if (eventData.substring(contentIdx, contentIdx + 15).contains("null")) return null

            val startIdx = contentIdx + contentKey.length
            val endIdx = findUnescapedQuoteEnd(eventData, startIdx)
            if (endIdx == -1) return null

            JsonUtils.unescapeJson(eventData.substring(startIdx, endIdx))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从非流式响应中解析完整的 tool_calls
     */
    fun parseToolCalls(responseJson: String): List<ToolCallData> {
        val toolCalls = mutableListOf<ToolCallData>()

        // 查找所有 tool_calls 块
        val tcKey = "\"tool_calls\":["
        val tcIdx = responseJson.indexOf(tcKey)
        if (tcIdx == -1) return toolCalls

        // 简单解析：逐个提取 id, name, arguments
        val idPattern = Regex(""""id"\s*:\s*"([^"]+)"""")
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
        val argsPattern = Regex(""""arguments"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        val section = responseJson.substring(tcIdx)
        val idMatches = idPattern.findAll(section).toList()
        val nameMatches = namePattern.findAll(section).toList()
        val argsMatches = argsPattern.findAll(section).toList()

        for (i in idMatches.indices) {
            val id = idMatches.getOrNull(i)?.groupValues?.getOrNull(1) ?: continue
            val name = nameMatches.getOrNull(i)?.groupValues?.getOrNull(1) ?: continue
            val args = argsMatches.getOrNull(i)?.groupValues?.getOrNull(1)?.let { JsonUtils.unescapeJson(it) } ?: "{}"
            toolCalls.add(ToolCallData(id = id, name = name, arguments = args))
        }

        return toolCalls
    }

    /**
     * 从 SSE 流事件中检测 finish_reason
     */
    fun parseFinishReason(eventData: String): String? {
        val key = "\"finish_reason\":\""
        val idx = eventData.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        val end = eventData.indexOf("\"", start)
        return if (end > start) eventData.substring(start, end) else null
    }

    /**
     * 从 SSE 流事件中累积解析 tool_calls
     */
    fun parseStreamToolCallDelta(eventData: String): ToolCallDelta? {
        // 检查是否包含 tool_calls delta
        val tcKey = "\"tool_calls\""
        if (!eventData.contains(tcKey)) return null

        val id = extractJsonString(eventData, "\"id\":\"")
        val name = extractJsonString(eventData, "\"name\":\"")
        val args = extractJsonString(eventData, "\"arguments\":\"")

        return ToolCallDelta(
            index = extractJsonInt(eventData, "\"index\":"),
            id = id,
            name = name,
            arguments = args
        )
    }

    private fun extractJsonString(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        val end = findUnescapedQuoteEnd(json, start)
        return if (end > start) JsonUtils.unescapeJson(json.substring(start, end)) else null
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        val end = json.indexOfFirst { it == ',' || it == '}' || it == '\n' }.takeIf { it > start } ?: return null
        return json.substring(start, end).trim().toIntOrNull()
    }

    private fun findUnescapedQuoteEnd(str: String, start: Int): Int {
        var i = start
        while (i < str.length) {
            when (str[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

}

/**
 * 流式 tool_calls delta 数据
 */
data class ToolCallDelta(
    val index: Int?,
    val id: String?,
    val name: String?,
    val arguments: String?
) {
    fun hasContent(): Boolean = id != null || name != null || arguments != null
}

data class ChatMessage(
    val role: String,
    val content: String
)
