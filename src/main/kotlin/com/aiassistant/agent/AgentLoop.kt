package com.aiassistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class AgentLoop(
    private val project: Project,
    private val session: AgentSession
) {
    private val settings = project.service<com.aiassistant.AppSettingsService>()
    private val apiKey =
        settings.getApiKey() ?: throw IllegalStateException("API Key not configured")

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl("https://api.deepseek.com/anthropic")
        .apiKey(apiKey)
        .build()

    private val model = "deepseek-v4-pro"
    private val toolExecutor = ToolExecutor(project, session)

    var onToken: ((String) -> Unit)? = null
    var onToolCall: ((String, Map<String, Any?>) -> Unit)? = null

    fun close() {
        session.cancel()
    }

    enum class AgentMode { CHAT, AGENT, PLAN }

    fun run(userMessage: String, mode: AgentMode = AgentMode.AGENT): Result {
        if (session.state != AgentSession.State.IDLE) {
            return Result.Error("Agent is already running")
        }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .addSystemMessage(buildSystemPrompt())
            .addUserMessage(userMessage)
            .apply { if (mode != AgentMode.CHAT) ToolRegistry.listAll().forEach { addTool(it) } }

        session.state = AgentSession.State.PROCESSING
        val output = StringBuilder()
        var turn = 0
        var lastStopReason = ""

        try {
            while (!session.cancelled) {
                // Streaming: accumulate text for UI and keep the full assistant turn for API replay.
                val turnOutput = StringBuilder()
                val accumulator = BetaMessageAccumulator.create()

                client.beta().messages().createStreaming(builder.build()).use { stream ->
                    stream.stream().forEach { event ->
                        accumulator.accumulate(event)
                        if (event.isContentBlockDelta()) {
                            val delta = event.asContentBlockDelta()
                            delta.delta().text().ifPresent { text ->
                                turnOutput.append(text.text())
                                onToken?.invoke(text.text())
                            }
                        } else if (event.isMessageDelta()) {
                            event.asMessageDelta().delta().stopReason().ifPresent {
                                lastStopReason = it.toString()
                            }
                        }
                    }
                }

                output.append(turnOutput)
                val assistantMessage = accumulator.message()
                val toolUses = assistantMessage.content().mapNotNull { it.toolUse().orElse(null) }
                lastStopReason =
                    assistantMessage.stopReason().map { it.toString() }.orElse(lastStopReason)

                // Check stop reason — end_turn means LLM is done
                if (lastStopReason.contains("end_turn") && toolUses.isEmpty()) break
                if (toolUses.isEmpty()) break

                val toolResultParams = mutableListOf<BetaContentBlockParam>()

                for (toolUse in toolUses) {
                    onToolCall?.invoke(toolUse.name(), ToolInput.map(toolUse._input()))
                    val result = toolExecutor.execute(toolUse)
                    toolResultParams.add(
                        BetaContentBlockParam.ofToolResult(
                            BetaToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .contentAsJson(result)
                                .build()
                        )
                    )
                }

                builder
                    .addMessage(assistantMessage)
                    .addUserMessageOfBetaContentBlockParams(toolResultParams)

                turn++
            }

            session.state = AgentSession.State.IDLE
            return Result.Success(output.toString(), turn, lastStopReason)

        } catch (e: Exception) {
            session.state = AgentSession.State.ERROR
            return Result.Error(e.message ?: "Unknown error")
        }
    }

    private fun buildSystemPrompt(): String {
        val skillExt = com.aiassistant.skills.SkillManager(project).getSystemPromptExtension()
        val projectName = project.name
        return """
你是 Code Assistant，运行在 JetBrains IDE 中。当前项目: $projectName。

## 可用工具
- readFile: 读取项目文件内容
- writeFile: 覆盖写入文件
- editFile: 精确替换文件中的部分内容（oldString 必须唯一匹配）
- runShell: 执行 Shell 命令（无超时限制）
- listFiles: 列出目录结构
- searchContent: 搜索文本内容
- readLints: 读取 IDE 诊断信息
- spawnAgent: 启动子代理

$skillExt
## 原则
1. 修改代码前先读取文件
2. editFile 的 oldString 必须精确匹配且唯一
3. 所有文件路径使用项目内相对路径
4. Shell 命令工作目录默认为项目根目录，长时间运行是正常的
5. 使用中文回复
        """.trimIndent()
    }

    sealed class Result {
        data class Success(val text: String, val turns: Int, val stopReason: String) : Result()
        data class Error(val message: String) : Result()
    }
}
