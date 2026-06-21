package com.aiassistant.agent.memory

import com.aiassistant.AnthropicMessage
import com.aiassistant.AnthropicSdkClient
import com.aiassistant.AnthropicToolDef
import com.aiassistant.AppLogger
import com.google.gson.Gson

/**
 * MemoryAutoExtract：在清空对话时自动从最近对话中提取值得跨会话记忆的关键信息。
 *
 * 使用 LLM 分析最近 20 条对话历史，提取用户偏好、反馈、项目约定等，
 * 通过 MemoryEngine 写入记忆存储。
 */
class MemoryAutoExtract(private val engine: MemoryEngine) {

    /**
     * 从对话历史中提取记忆并写入 MemoryEngine。
     *
     * @param conversationHistory 完整的跨轮对话历史（AnthropicMessage 列表）
     * @param apiKey DeepSeek API Key
     * @return 成功提取的记忆条数
     */
    fun extract(conversationHistory: List<AnthropicMessage>, apiKey: String): Int {
        if (conversationHistory.isEmpty()) return 0

        // 仅取最近 20 条，每条截断 500 字符防止 prompt 过长
        val convoText = conversationHistory.takeLast(20)
            .joinToString("\n\n") { "[${it.role}]: ${it.content.take(500)}" }

        val prompt = buildString {
            appendLine("分析以下对话，提取值得跨会话记忆的关键信息。")
            appendLine("输出 JSON 数组（不要其他文字），每项包含：name(kebab-case), description, content(含 Why 和 How to apply), type(user/feedback/project/reference), scope(user/project)")
            appendLine()
            appendLine("提取规则：")
            appendLine("1. 用户明确表达的偏好、习惯 → type=user")
            appendLine("2. 用户给你的反馈、纠正你的行为 → type=feedback")
            appendLine("3. 项目架构约定、已做决策 → type=project")
            appendLine("4. 外部资料/文档引用 → type=reference")
            appendLine("5. 不要提取一次性问题答案、简单询问、临时代码片段")
            appendLine("6. content 中必须包含 **Why:** 和 **How to apply:** 两行")
            appendLine()
            appendLine("对话原文：")
            appendLine(convoText)
            appendLine()
            appendLine("JSON:")
        }

        val client = AnthropicSdkClient(apiKey)
        return try {
            val latch = java.util.concurrent.CountDownLatch(1)
            var resultText = ""
            val messages = listOf(AnthropicMessage("user", prompt))
            client.createStreaming(
                model = com.aiassistant.AppSettingsService.getInstance().getModel() ?: "deepseek-v4-pro",
                systemPrompt = "",
                messages = messages,
                tools = emptyList(),
                thinkingEnabled = false,
                callback = object : AnthropicSdkClient.Callback {
                    override fun onTextDelta(fullText: String) { resultText = fullText }
                    override fun onStreamComplete(
                        textContent: String, thinking: String, thinkingSignature: String,
                        toolCalls: List<AnthropicSdkClient.StreamToolCall>,
                        inputTokens: Int, outputTokens: Int, stopReason: String
                    ) { latch.countDown() }
                    override fun onError(error: Throwable) { latch.countDown() }
                    override fun onToolInputDelta(partial: String) {}
                    override fun onToolUseStart(id: String, name: String) {}
                    override fun onThinkingDelta(fullThinking: String) {}
                }
            )
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)

            val parsed = parseResult(resultText)
            var count = 0
            for (entry in parsed) {
                if (entry.name.isNotBlank() && entry.content.isNotBlank()) {
                    engine.write(entry).onSuccess { count++ }
                }
            }
            if (count > 0) {
                AppLogger.info("MemoryAutoExtract: 自动提取了 $count 条记忆")
            }
            count
        } catch (e: Exception) {
            AppLogger.warn("MemoryAutoExtract: 自动提取失败: ${e.message}")
            0
        } finally {
            client.close()
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组文本，提取 MemoryEntry 列表。
     * 容错：跳过无法解析的条目，仅在整体非 JSON 时返回空列表。
     * 额外容错：超时导致 JSON 数组未闭合时，尝试手动闭合最后一项。
     */
    private fun parseResult(text: String): List<MemoryEntry> {
        return try {
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']')
            if (jsonStart < 0) return emptyList()
            val json: String
            if (jsonEnd < 0) {
                // 数组未闭合（超时导致不完整 JSON），尝试手动闭合最后一项
                val partial = text.substring(jsonStart)
                val lastBrace = partial.lastIndexOf('}')
                if (lastBrace >= 0) {
                    json = partial.substring(0, lastBrace + 1) + "]"
                } else {
                    return emptyList()
                }
            } else {
                json = text.substring(jsonStart, jsonEnd + 1)
            }
            val gson = Gson()
            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val arr: List<Map<String, Any>> = gson.fromJson(json, listType)
            arr.mapNotNull { item ->
                try {
                    MemoryEntry(
                        name = item["name"]?.toString() ?: "",
                        description = item["description"]?.toString() ?: "",
                        content = item["content"]?.toString() ?: "",
                        type = item["type"]?.toString() ?: "project",
                        scope = item["scope"]?.toString() ?: "project"
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}
