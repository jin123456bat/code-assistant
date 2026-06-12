package com.aiassistant

import com.aiassistant.agent.ImageData
import com.aiassistant.shared.JsonUtils

/**
 * DeepSeek Anthropic 兼容 API 适配器。
 * 使用 Anthropic Messages API 格式与 DeepSeek v4 模型通信。
 */
class AnthropicAdapter(
    val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/anthropic/v1/messages"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val MAX_TOKENS = 4096
    }

    /**
     * 构建 Anthropic 格式请求体。
     * tools 格式: [{"name":"git_status","description":"...","input_schema":{...}}]
     */
    fun buildRequest(
        systemPrompt: String,
        messages: List<AnthropicMessage>,
        toolsJson: String,
        toolChoice: String = "auto",
        maxTokens: Int = MAX_TOKENS,
        stream: Boolean = true,
        modelOverride: String? = null
    ): String {
        val effectiveModel = modelOverride ?: model
        val systemEscaped = JsonUtils.escapeJson(systemPrompt)
        val msgs = messages.joinToString(",") { msg ->
            val contentBlocks = when {
                msg.toolCallId != null -> {
                    // tool_result
                    val content = JsonUtils.escapeJson(msg.content)
                    """[{"type":"tool_result","tool_use_id":"${msg.toolCallId}","content":"$content"}]"""
                }
                msg.toolUseId != null -> {
                    // assistant with tool_use — prepend empty thinking block for DeepSeek V4 compatibility
                    val input = msg.toolInput.ifEmpty { "{}" }
                    """[{"type":"thinking","thinking":""},{"type":"tool_use","id":"${msg.toolUseId}","name":"${msg.toolName ?: "unknown"}","input":$input}]"""
                }
                msg.role == "assistant" -> {
                    val text = JsonUtils.escapeJson(msg.content)
                    """[{"type":"text","text":"$text"}]"""
                }
                else -> {
                    // 用户消息：如有图片则生成 Claude 原生 image 块
                    val blocks = mutableListOf<String>()
                    msg.images?.forEach { img ->
                        val imgData = JsonUtils.escapeJson(img.data)
                        blocks.add("""{"type":"image","source":{"type":"base64","media_type":"${img.mediaType}","data":"$imgData"}}""")
                    }
                    if (msg.content.isNotBlank()) {
                        val text = JsonUtils.escapeJson(msg.content)
                        blocks.add("""{"type":"text","text":"$text"}""")
                    }
                    "[${blocks.joinToString(",")}]"
                }
            }
            """{"role":"${msg.role}","content":$contentBlocks}"""
        }

        val toolChoiceBlock = when (toolChoice) {
            "required" -> ""","tool_choice":{"type":"any"}"""
            else -> ""","tool_choice":{"type":"auto"}"""
        }

        return """{"model":"$effectiveModel","max_tokens":$maxTokens,"system":"$systemEscaped","messages":[$msgs],"tools":$toolsJson$toolChoiceBlock,"stream":$stream}"""
    }

    /**
     * 解析 Anthropic SSE 流事件，提取文本增量和工具调用。
     * 返回 ParsedEvent 或 null（无法解析/非数据事件）
     */
    fun parseSseEvent(line: String): ParsedEvent? {
        // SseClient 已经去掉了 "data: " 前缀，这里接收的是裸 JSON
        val json = line.trim()
        if (json.isEmpty() || json[0] != '{') return null

        // 提取 type 字段
        val type = extractJsonString(json, "\"type\":\"")
            ?: return null

        return when (type) {
            "message_start" -> ParsedEvent.MessageStart
            "content_block_delta" -> {
                val deltaType = extractJsonString(json, "\"delta\":{\"type\":\"")
                when (deltaType) {
                    "text_delta" -> {
                        val text = extractJsonString(json, "\"text\":\"")
                        ParsedEvent.TextDelta(text ?: "")
                    }
                    "input_json_delta" -> {
                        val partial = extractJsonString(json, "\"partial_json\":\"")
                        ParsedEvent.InputJsonDelta(partial ?: "")
                    }
                    "thinking_delta" -> {
                        val thinking = extractJsonString(json, "\"thinking\":\"") ?: ""
                        ParsedEvent.ThinkingDelta(thinking)
                    }
                    "signature_delta" -> {
                        val sig = extractJsonString(json, "\"signature\":\"") ?: ""
                        ParsedEvent.SignatureDelta(sig)
                    }
                    else -> null
                }
            }
            "content_block_start" -> {
                val blockType = extractJsonString(json, "\"content_block\":{\"type\":\"")
                when (blockType) {
                    "tool_use" -> {
                        val id = extractJsonString(json, "\"id\":\"") ?: ""
                        val name = extractJsonString(json, "\"name\":\"") ?: ""
                        ParsedEvent.ToolUseStart(id, name)
                    }
                    "text" -> ParsedEvent.TextStart
                    "thinking" -> ParsedEvent.ThinkingStart
                    else -> null
                }
            }
            "content_block_stop" -> ParsedEvent.ContentBlockStop
            "message_delta" -> {
                val stopReason = extractJsonString(json, "\"stop_reason\":\"")
                ParsedEvent.MessageDelta(stopReason)
            }
            "message_stop" -> ParsedEvent.MessageStop
            else -> null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        var i = start
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return JsonUtils.unescapeJson(json.substring(start, i))
                else -> i++
            }
        }
        return null
    }
}

/** Anthropic 格式消息 */
data class AnthropicMessage(
    val role: String,         // "user" | "assistant"
    val content: String,      // 文本内容
    val toolCallId: String? = null,    // tool_result 的 tool_use_id
    val toolUseId: String? = null,     // tool_use 的 id
    val toolName: String? = null,      // tool_use 的 name
    val toolInput: String = "",        // tool_use 的 input JSON
    val images: List<ImageData>? = null // 用户消息附带的图片（Claude 原生 image 块）
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
