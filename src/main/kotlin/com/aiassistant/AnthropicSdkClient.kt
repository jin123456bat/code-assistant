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
        fun onStreamComplete(textContent: String, thinking: String, toolCalls: List<StreamToolCall>)
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

        for (msg in messages) {
            val sdkMsg = buildSdkMessage(msg) ?: continue
            paramsBuilder.addMessage(sdkMsg)
        }

        val params = paramsBuilder.build()
        val latch = CountDownLatch(1)
        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        val toolInputBuffer = StringBuilder()
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolCalls = mutableListOf<StreamToolCall>()

        try {
            client.messages().createStreaming(params).use { response ->
                response.stream().forEach { event ->
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
                    } else if (event.isContentBlockStop()) {
                        if (currentToolId != null && currentToolName != null) {
                            toolCalls.add(StreamToolCall(currentToolId!!, currentToolName!!, toolInputBuffer.toString()))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                        }
                    } else if (event.isMessageStop()) {
                        callback.onStreamComplete(
                            textBuffer.toString(),
                            thinkingBuffer.toString(),
                            toolCalls.toList()
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

    private fun buildSdkMessage(msg: AnthropicMessage): MessageParam? {
        val blocks = mutableListOf<ContentBlockParam>()
        when {
            msg.toolCallId != null -> {
                blocks.add(
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(msg.toolCallId!!)
                            .content(msg.content)
                            .build()
                    )
                )
            }
            msg.toolUseId != null -> {
                // assistant 消息中的 tool_use 块——多轮工具调用时回传到 API
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
            msg.role == "user" -> {
                if (msg.content.isNotBlank()) {
                    blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(msg.content).build()
                    ))
                }
            }
            msg.role == "assistant" -> {
                if (msg.content.isNotBlank()) {
                    blocks.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(msg.content).build()
                    ))
                }
            }
            else -> return null
        }
        if (blocks.isEmpty()) return null

        val role = when (msg.role) {
            "user" -> MessageParam.Role.USER
            "assistant" -> MessageParam.Role.ASSISTANT
            else -> MessageParam.Role.USER
        }
        return MessageParam.builder()
            .role(role)
            .contentOfBlockParams(blocks)
            .build()
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
