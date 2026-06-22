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
    const val MAX_TOKENS = 32768L

    /** 创建支持 AnthropicMessage 密封类多态序列化的 Gson 实例（用于 forkHistory 序列化/反序列化） */
    fun createGson(): com.google.gson.Gson = com.google.gson.GsonBuilder()
        .registerTypeHierarchyAdapter(AnthropicMessage::class.java, AnthropicMessageAdapter())
        .create()
}

/** Anthropic 格式消息 — 密封类层次，API 协议层类型 */
sealed interface AnthropicMessage

/** 用户消息（含文本、图片、轮次分组） */
data class UserMessage(
    val content: String,
    val images: List<ImageData>? = null,
    val groupId: Int = 0
) : AnthropicMessage

/** AI 助手消息（单条消息含 thinking + text + 多个 tool_use，对齐 Anthropic API 多 block 结构） */
data class AssistantMessage(
    val text: String,
    val thinking: String = "",
    val thinkingSignature: String = "",
    val toolUses: List<ToolUseParams> = emptyList()
) : AnthropicMessage

/** 工具执行结果（role=user，通过 toolCallId 关联 tool_use） */
data class ToolResultMessage(
    val content: String,
    val toolCallId: String,
    val groupId: Int = 0
) : AnthropicMessage

/** 工具调用参数（AssistantMessage.toolUses 的元素） */
data class ToolUseParams(
    val id: String,
    val name: String,
    val input: String
)

/** AnthropicMessage 密封类多态序列化适配器（供 forkHistory 使用） */
private class AnthropicMessageAdapter : com.google.gson.JsonSerializer<AnthropicMessage>, com.google.gson.JsonDeserializer<AnthropicMessage> {
    override fun serialize(src: AnthropicMessage, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
        val obj = com.google.gson.JsonObject()
        when (src) {
            is UserMessage -> {
                obj.addProperty("__type", "user")
                obj.addProperty("content", src.content)
                if (!src.images.isNullOrEmpty()) {
                    val imgArray = com.google.gson.JsonArray()
                    for (img in src.images) {
                        val imgObj = com.google.gson.JsonObject()
                        imgObj.addProperty("mediaType", img.mediaType)
                        imgObj.addProperty("data", img.data)
                        imgArray.add(imgObj)
                    }
                    obj.add("images", imgArray)
                }
                obj.addProperty("groupId", src.groupId)
            }
            is AssistantMessage -> {
                obj.addProperty("__type", "assistant")
                obj.addProperty("text", src.text)
                if (src.thinking.isNotBlank()) obj.addProperty("thinking", src.thinking)
                if (src.thinkingSignature.isNotBlank()) obj.addProperty("thinkingSignature", src.thinkingSignature)
                val tuArray = com.google.gson.JsonArray()
                for (tu in src.toolUses) {
                    val tuObj = com.google.gson.JsonObject()
                    tuObj.addProperty("id", tu.id)
                    tuObj.addProperty("name", tu.name)
                    tuObj.addProperty("input", tu.input)
                    tuArray.add(tuObj)
                }
                obj.add("toolUses", tuArray)
            }
            is ToolResultMessage -> {
                obj.addProperty("__type", "tool_result")
                obj.addProperty("content", src.content)
                obj.addProperty("toolCallId", src.toolCallId)
                obj.addProperty("groupId", src.groupId)
            }
        }
        return obj
    }

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): AnthropicMessage {
        val obj = json.asJsonObject
        return when (obj.get("__type")?.asString) {
            "user" -> {
                val images: List<ImageData>? = obj.getAsJsonArray("images")?.map { imgEl ->
                    val imgObj = imgEl.asJsonObject
                    ImageData(
                        mediaType = imgObj.get("mediaType")?.asString ?: "image/png",
                        data = imgObj.get("data")?.asString ?: ""
                    )
                }?.takeIf { it.isNotEmpty() }
                UserMessage(
                    content = obj.get("content")?.asString ?: "",
                    images = images,
                    groupId = obj.get("groupId")?.asInt ?: 0
                )
            }
            "assistant" -> {
                val tuArray = obj.getAsJsonArray("toolUses") ?: com.google.gson.JsonArray()
                val toolUses = tuArray.map { tu ->
                    val tuObj = tu.asJsonObject
                    ToolUseParams(
                        id = tuObj.get("id")?.asString ?: "",
                        name = tuObj.get("name")?.asString ?: "",
                        input = tuObj.get("input")?.asString ?: ""
                    )
                }
                AssistantMessage(
                    text = obj.get("text")?.asString ?: "",
                    thinking = obj.get("thinking")?.asString ?: "",
                    thinkingSignature = obj.get("thinkingSignature")?.asString ?: "",
                    toolUses = toolUses
                )
            }
            "tool_result" -> ToolResultMessage(
                content = obj.get("content")?.asString ?: "",
                toolCallId = obj.get("toolCallId")?.asString ?: "",
                groupId = obj.get("groupId")?.asInt ?: 0
            )
            else -> UserMessage(content = "")  // fallback
        }
    }
}

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
