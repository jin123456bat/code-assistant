package com.aiassistant

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnthropicSdkClient(
    apiKey: String,
    baseUrl: String = "https://api.deepseek.com/anthropic"
) {
    private val client = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .baseUrl(baseUrl)
        .maxRetries(3)
        .timeout(Duration.ofSeconds(120))
        .logLevel(com.anthropic.core.LogLevel.INFO)
        .build()

    interface Callback {
        fun onTextDelta(fullText: String)
        fun onThinkingDelta(fullThinking: String)
        fun onToolUseStart(id: String, name: String)
        fun onToolInputDelta(partial: String)
        fun onStreamComplete(textContent: String, thinking: String, thinkingSignature: String, toolCalls: List<StreamToolCall>, inputTokens: Int)
        fun onError(error: Throwable)
    }

    data class StreamToolCall(val id: String, val name: String, val arguments: String)

    fun createStreaming(
        model: String,
        systemPrompt: String,
        messages: List<AnthropicMessage>,
        tools: List<AnthropicToolDef>,
        thinkingEnabled: Boolean,
        maxTokens: Long = 4096,
        callback: Callback
    ) {
        val paramsBuilder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(systemPrompt)

        if (thinkingEnabled) {
            paramsBuilder.enabledThinking(8192)
        }

        for (td in tools) {
            val propsBuilder = Tool.InputSchema.Properties.builder()
            td.properties?.forEach { (name, prop) ->
                propsBuilder.putAdditionalProperty(name, com.anthropic.core.JsonValue.from(
                    mapOf("type" to prop.type, "description" to prop.description)
                ))
            }
            val schemaBuilder = Tool.InputSchema.builder()
                .properties(propsBuilder.build())
            if (td.required != null) {
                schemaBuilder.required(td.required)
            }
            paramsBuilder.addTool(
                Tool.builder()
                    .name(td.name)
                    .description(td.description)
                    .inputSchema(schemaBuilder.build())
                    .build()
            )
        }
        if (tools.isNotEmpty()) {
            paramsBuilder.toolChoice(ToolChoiceAuto.builder().build())
        }

        // 合并连续同 role 消息为单个 MessageParam（如 thinking + text + tool_use 属于同一 assistant 轮次）
        val mergedMessages = mergeConsecutiveSameRole(messages)
        com.aiassistant.AppLogger.info("SDK消息合并: ${messages.size}条 → ${mergedMessages.size}条")
        for (msg in mergedMessages) {
            val sdkMsg = buildSdkMessage(msg) ?: continue
            paramsBuilder.addMessage(sdkMsg)
        }

        val params = paramsBuilder.build()
        val msgSummary = messages.joinToString(" | ") { "${it.role}:${it.content.take(80)}${if (it.content.length > 80) "..." else ""}" }
        val toolSummary = tools.joinToString(", ") { it.name }
        val msgsDump = messages.joinToString("\n  ") { "${it.role}: ${it.content}" }
        com.aiassistant.AppLogger.info("SDK请求: model=$model thinking=$thinkingEnabled tools=[$toolSummary]\n  $msgsDump")
        val latch = CountDownLatch(1)
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        val toolInputBuffer = StringBuilder()
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolCalls = mutableListOf<StreamToolCall>()
        var currentThinkingSignature: String? = null
        val accumulator = com.anthropic.helpers.MessageAccumulator.create()

        try {
            client.messages().createStreaming(params).use { response ->
                response.stream().forEach { event ->
                    accumulator.accumulate(event)
                    if (event.isContentBlockDelta()) {
                        val delta = event.asContentBlockDelta().delta()
                        if (delta.isText()) {
                            textBuffer.append(delta.asText().text())
                            callback.onTextDelta(textBuffer.toString())
                        } else if (delta.isInputJson()) {
                            val partial = delta.asInputJson().partialJson()
                            toolInputBuffer.append(partial)
                            callback.onToolInputDelta(partial)
                        } else if (delta.isThinking()) {
                            thinkingBuffer.append(delta.asThinking().thinking())
                            callback.onThinkingDelta(thinkingBuffer.toString())
                        }
                    } else if (event.isContentBlockStart()) {
                        val block = event.asContentBlockStart().contentBlock()
                        block.toolUse().ifPresent { tu ->
                            currentToolId = tu.id()
                            currentToolName = tu.name()
                            toolInputBuffer.clear()
                            callback.onToolUseStart(tu.id(), tu.name())
                        }
                        block.thinking().ifPresent { th ->
                            currentThinkingSignature = th.signature()
                            com.aiassistant.AppLogger.info("SDK thinking block start: sigLen=${currentThinkingSignature?.length ?: 0} thinkingLen=${th.thinking().length}")
                        }
                        if (block.isThinking() && block.thinking().isEmpty) {
                            com.aiassistant.AppLogger.warn("SDK: isThinking=true but thinking() is empty!")
                        }
                    } else if (event.isContentBlockStop()) {
                        if (currentToolId != null && currentToolName != null) {
                            toolCalls.add(StreamToolCall(currentToolId!!, currentToolName!!, toolInputBuffer.toString()))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                        }
                    } else if (event.isMessageStop()) {
                        val inputTokens = try {
                            accumulator.message().usage().inputTokens().toInt()
                        } catch (_: Exception) { 0 }
                        com.aiassistant.AppLogger.info("SDK响应: text=${textBuffer} inputTokens=$inputTokens")
                        callback.onStreamComplete(
                            textBuffer.toString(),
                            thinkingBuffer.toString(),
                            currentThinkingSignature ?: "",
                            toolCalls.toList(),
                            inputTokens
                        )
                        latch.countDown()
                    }
                }
            }
        } catch (e: Exception) {
            callback.onError(e)
            latch.countDown()
        }

        latch.await(10, TimeUnit.MINUTES)
    }

    /**
     * 合并连续同 role 消息为单个消息，将多个 content block 聚合到一个 MessageParam。
     * 例如 assistant 的 thinking + text + tool_use 需要作为同一个消息的多个 content block 传回 API。
     */
    private fun mergeConsecutiveSameRole(messages: List<AnthropicMessage>): List<AnthropicMessage> {
        if (messages.isEmpty()) return messages
        val result = mutableListOf<AnthropicMessage>()
        var i = 0
        while (i < messages.size) {
            val current = messages[i]
            // tool_result 消息不合并（必须单独作为 user 消息）
            if (current.toolCallId != null) {
                result.add(current)
                i++
                continue
            }
            // 收集连续同 role + 同 groupId 的非 tool_result 消息（跨轮不合并）
            val group = mutableListOf<AnthropicMessage>()
            while (i < messages.size && messages[i].role == current.role
                && messages[i].toolCallId == null && messages[i].groupId == current.groupId) {
                group.add(messages[i])
                i++
            }
            if (group.size == 1) {
                result.add(group[0])
            } else {
                // 合并：聚合 thinking + text + tool_use
                var mergedThinking = ""
                var mergedThinkingSig = ""
                var mergedContent = ""
                var mergedToolUseId: String? = null
                var mergedToolName: String? = null
                var mergedToolInput = ""
                for (m in group) {
                    if (m.thinking.isNotBlank()) {
                        mergedThinking = m.thinking
                        mergedThinkingSig = m.thinkingSignature
                    }
                    if (m.content.isNotBlank()) { mergedContent = m.content }
                    if (m.toolUseId != null) {
                        mergedToolUseId = m.toolUseId
                        mergedToolName = m.toolName
                        mergedToolInput = m.toolInput
                    }
                }
                result.add(AnthropicMessage(
                    role = current.role,
                    content = mergedContent,
                    toolUseId = mergedToolUseId,
                    toolName = mergedToolName,
                    toolInput = mergedToolInput,
                    thinking = mergedThinking,
                    thinkingSignature = mergedThinkingSig
                ))
            }
        }
        return result
    }

    private fun buildSdkMessage(msg: AnthropicMessage): MessageParam? {
        val blocks = mutableListOf<ContentBlockParam>()

        // 处理 tool_result（user 消息中的工具结果回传）
        if (msg.toolCallId != null) {
            blocks.add(
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(msg.toolCallId!!)
                        .content(msg.content)
                        .build()
                )
            )
            if (blocks.isNotEmpty()) {
                return MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(blocks)
                    .build()
            }
            return null
        }

        // assistant 消息：thinking + text + tool_use 按需累加（合并后单消息可能含多种 block）
        if (msg.role == "assistant") {
            // thinking block：thinking 模式下必须随后续请求传回（SDK 要求 signature 非 null，DeepSeek V4 可能无签名则传空字符串）
            if (msg.thinking.isNotBlank()) {
                com.aiassistant.AppLogger.info("SDK构建: 添加thinking block thinkingLen=${msg.thinking.length} sigLen=${msg.thinkingSignature.length}")
                blocks.add(ContentBlockParam.ofThinking(
                    ThinkingBlockParam.builder()
                        .thinking(msg.thinking)
                        .signature(msg.thinkingSignature)  // 空字符串也传，满足 SDK build() 的 checkRequired
                        .build()
                ))
            }
            // 文本 block
            if (msg.content.isNotBlank()) {
                blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(msg.content).build()
                ))
            }
            // tool_use block
            if (msg.toolUseId != null) {
                val inputBuilder = ToolUseBlockParam.Input.builder()
                try {
                    val gson = com.google.gson.Gson()
                    val inputMap = gson.fromJson(msg.toolInput.ifEmpty { "{}" }, Map::class.java) as? Map<*, *>
                    inputMap?.forEach { (k, v) ->
                        if (k != null) {
                            inputBuilder.putAdditionalProperty(k.toString(),
                                com.anthropic.core.JsonValue.from(v ?: ""))
                        }
                    }
                } catch (_: Exception) {}
                blocks.add(ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder()
                        .id(msg.toolUseId!!)
                        .name(msg.toolName ?: "unknown")
                        .input(inputBuilder.build())
                        .build()
                ))
            }
            if (blocks.isEmpty()) return null
            return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build()
        }

        // user 消息：纯文本
        if (msg.role == "user") {
            if (msg.content.isNotBlank()) {
                blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(msg.content).build()
                ))
            }
            if (blocks.isEmpty()) return null
            return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(blocks)
                .build()
        }

        return null
    }
}

data class AnthropicToolDef(
    val name: String,
    val description: String,
    val properties: Map<String, PropertyDef>? = null,
    val required: List<String>? = null
)

data class PropertyDef(
    val type: String,
    val description: String
)
