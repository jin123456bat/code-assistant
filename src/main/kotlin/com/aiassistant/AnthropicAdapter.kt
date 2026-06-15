package com.aiassistant

import com.aiassistant.agent.ImageData
import com.aiassistant.shared.JsonUtils

/**
 * Anthropic API 类型定义（消息格式、SSE 事件）。
 * HTTP/SSE 层已迁移到 AnthropicSdkClient（官方 Java SDK），
 * 此类仅保留数据类供测试和历史参考。
 */
object AnthropicAdapter {
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
    const val DEFAULT_MODEL = "deepseek-v4-pro"
    const val MAX_TOKENS = 4096
}

/** Anthropic 格式消息 */
data class AnthropicMessage(
    val role: String,         // "user" | "assistant"
    val content: String,      // 文本内容
    val toolCallId: String? = null,    // tool_result 的 tool_use_id
    val toolUseId: String? = null,     // tool_use 的 id
    val toolName: String? = null,      // tool_use 的 name
    val toolInput: String = "",        // tool_use 的 input JSON
    val images: List<ImageData>? = null, // 用户消息附带的图片（Claude 原生 image 块）
    val thinking: String = "",          // thinking 内容（DeepSeek V4 thinking 模式，必须随后续请求回传）
    val thinkingSignature: String = ""  // thinking 签名（DeepSeek V4 thinking 模式，必须随后续请求回传）
)

/** SSE 事件解析结果 */
sealed class ParsedEvent {
    object MessageStart : ParsedEvent()
    data class TextDelta(val text: String) : ParsedEvent()
    data class InputJsonDelta(val partial: String) : ParsedEvent()
    data class ThinkingDelta(val thinking: String) : ParsedEvent()
    data class SignatureDelta(val signature: String) : ParsedEvent()
    data class ToolUseStart(val id: String, val name: String) : ParsedEvent()
    object TextStart : ParsedEvent()
    object ThinkingStart : ParsedEvent()
    object ContentBlockStop : ParsedEvent()
    data class MessageDelta(val stopReason: String?) : ParsedEvent()
    object MessageStop : ParsedEvent()
}
