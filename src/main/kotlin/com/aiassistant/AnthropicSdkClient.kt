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
    companion object {
        /**
         * 网络预检：DNS 解析 + TCP 443 连通性检查。
         * 返回 null 表示正常，返回非 null 字符串展示给用户。
         */
        fun preflightCheck(baseUrl: String = "https://api.deepseek.com/anthropic"): String? {
            val hostname = try {
                java.net.URI(baseUrl).host ?: "api.deepseek.com"
            } catch (_: Exception) {
                "api.deepseek.com"
            }
            try {
                val addr = java.net.InetAddress.getByName(hostname)
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(addr, 443), 5000)
                    }
                } catch (e: Exception) {
                    return AiAssistantBundle.message("error.connection.timeout", hostname, e.message ?: "timeout")
                }
            } catch (e: java.net.UnknownHostException) {
                return AiAssistantBundle.message("error.connection.dns", hostname)
            } catch (e: Exception) {
                return AiAssistantBundle.message("error.connection.generic", e.message?.take(80) ?: e.javaClass.simpleName)
            }
            return null
        }
    }

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
        fun onStreamComplete(textContent: String, thinking: String, thinkingSignature: String, toolCalls: List<StreamToolCall>, inputTokens: Int, outputTokens: Int, stopReason: String)
        fun onError(error: Throwable)
    }

    data class StreamToolCall(val id: String, val name: String, val arguments: String)

    fun createStreaming(
        model: String,
        systemPrompt: String,
        messages: List<AnthropicMessage>,
        tools: List<AnthropicToolDef>,
        thinkingEnabled: Boolean,
        maxTokens: Long = 32768,
        callback: Callback
    ) {
        val paramsBuilder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(systemPrompt)

        // DeepSeek V4 不完全支持 Anthropic thinking 协议（无签名，回传 400），禁用 thinking
        // if (thinkingEnabled) { paramsBuilder.enabledThinking(8192) }

        for (td in tools) {
            val propsBuilder = Tool.InputSchema.Properties.builder()
            td.properties?.forEach { (name, prop) ->
                val propMap = mutableMapOf<String, Any>("type" to prop.type, "description" to prop.description)
                if (prop.enum != null) {
                    propMap["enum"] = prop.enum
                }
                propsBuilder.putAdditionalProperty(name, com.anthropic.core.JsonValue.from(propMap))
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

        for (msg in messages) {
            val sdkMsg = buildSdkMessage(msg) ?: continue
            paramsBuilder.addMessage(sdkMsg)
        }

        val params = paramsBuilder.build()
        AppLogger.info("SDK请求: model=$model thinking=$thinkingEnabled tools=[${tools.joinToString(", ") { it.name }}]")
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
                    if (event.isMessageDelta()) {
                        val deltaEvent = event.asMessageDelta()
                        val deltaUsage = deltaEvent.usage()
                        AppLogger.info("SDK message_delta: outputTokens=${deltaUsage.outputTokens()} inputTokens=${deltaUsage.inputTokens()}")
                    }
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
                        val message = accumulator.message()
                        val rawUsage = try { message.usage() } catch (e: Exception) { null }
                        val inputTokens = try { rawUsage?.inputTokens()?.toInt() ?: 0 } catch (_: Exception) { 0 }
                        val outputTokens = try { rawUsage?.outputTokens()?.toInt() ?: 0 } catch (_: Exception) { 0 }
                        val stopReason = try {
                            message.stopReason().map { it.toString() }.orElse("end_turn")
                        } catch (_: Exception) { "end_turn" }
                        if (rawUsage == null) com.aiassistant.AppLogger.warn("SDK: message_stop 中 usage 为 null，inputTokens=$inputTokens outputTokens=$outputTokens")
                        com.aiassistant.AppLogger.info("SDK响应: text=${textBuffer} inputTokens=$inputTokens outputTokens=$outputTokens stopReason=$stopReason")
                        callback.onStreamComplete(
                            textBuffer.toString(),
                            thinkingBuffer.toString(),
                            currentThinkingSignature ?: "",
                            toolCalls.toList(),
                            inputTokens,
                            outputTokens,
                            stopReason
                        )
                        latch.countDown()
                    }
                }
                // forEach 正常退出但未收到 message_stop（服务器优雅关闭 SSE 流）：
                // 通知上层释放 doneLatch（AgentLoop 层），避免 120 秒二次等待。
                // callAnthropic() 有 hasResponse 标志保护——若已收到部分数据则使用之，不会丢失。
                if (latch.count > 0) {
                    try {
                        callback.onError(RuntimeException("SSE stream ended without message_stop"))
                    } catch (_: Exception) {
                        // 确保 onError 异常不阻止 latch 释放
                    }
                }
            }
        } catch (e: Exception) {
            callback.onError(e)
        } finally {
            latch.countDown()  // 无论何种退出路径都释放，防止 Agent 线程永久阻塞
        }

        latch.await(10, TimeUnit.MINUTES)
    }

    private fun buildSdkMessage(msg: AnthropicMessage): MessageParam? {
        return when (msg) {
            is ToolResultMessage -> buildToolResult(msg)
            is AssistantMessage -> buildAssistant(msg)
            is UserMessage -> buildUser(msg)
        }
    }

    private fun buildToolResult(msg: ToolResultMessage): MessageParam {
        return MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(listOf(
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(msg.toolCallId)
                        .content(msg.content)
                        .build()
                )
            ))
            .build()
    }

    private fun buildAssistant(msg: AssistantMessage): MessageParam? {
        val blocks = mutableListOf<ContentBlockParam>()
        if (msg.thinking.isNotBlank()) {
            blocks.add(ContentBlockParam.ofThinking(
                ThinkingBlockParam.builder()
                    .thinking(msg.thinking)
                    .signature(msg.thinkingSignature)
                    .build()
            ))
        }
        if (msg.text.isNotBlank()) {
            blocks.add(ContentBlockParam.ofText(
                TextBlockParam.builder().text(msg.text).build()
            ))
        }
        for (tu in msg.toolUses) {
            val inputBuilder = buildToolInput(tu.input) ?: return null
            blocks.add(ContentBlockParam.ofToolUse(
                ToolUseBlockParam.builder()
                    .id(tu.id)
                    .name(tu.name)
                    .input(inputBuilder)
                    .build()
            ))
        }
        if (blocks.isEmpty()) return null
        return MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(blocks)
            .build()
    }

    private fun buildUser(msg: UserMessage): MessageParam? {
        val blocks = mutableListOf<ContentBlockParam>()
        if (msg.content.isNotBlank()) {
            blocks.add(ContentBlockParam.ofText(
                TextBlockParam.builder().text(msg.content).build()
            ))
        }
        msg.images?.forEach { img ->
            blocks.add(ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(ImageBlockParam.Source.ofBase64(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.of(img.mediaType))
                            .data(img.data)
                            .build()
                    ))
                    .build()
            ))
        }
        if (blocks.isEmpty()) return null
        return MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(blocks)
            .build()
    }

    /** 解析 tool_use input JSON 为 SDK Input 对象，失败返回 null */
    private fun buildToolInput(jsonInput: String): ToolUseBlockParam.Input? {
        return try {
            val gson = com.google.gson.GsonBuilder()
                .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
                .create()
            val inputMap = gson.fromJson(jsonInput.ifEmpty { "{}" }, Map::class.java) as? Map<*, *>
            val builder = ToolUseBlockParam.Input.builder()
            inputMap?.forEach { (k, v) ->
                if (k != null) {
                    builder.putAdditionalProperty(k.toString(), com.anthropic.core.JsonValue.from(v ?: ""))
                }
            }
            builder.build()
        } catch (e: Exception) {
            com.aiassistant.AppLogger.warn("SDK tool_use input JSON 解析失败: ${jsonInput.take(200)}: ${e.message}")
            null
        }
    }

    /** 关闭底层 OkHttp 客户端，释放连接池和线程池 */
    fun close() {
        try { client.close() } catch (_: Exception) {}
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
    val description: String,
    val enum: List<String>? = null
)
