package com.aiassistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.net.SocketTimeoutException

class AgentLoop(
    private val project: Project,
    private val session: AgentSession
) {
    private val settings = project.service<com.aiassistant.AppSettingsService>()
    private val apiKey =
        settings.getApiKey() ?: throw IllegalStateException("API Key not configured")

    private var client: AnthropicClient = buildClient()

    private fun buildClient() = AnthropicOkHttpClient.builder()
        .baseUrl("https://api.deepseek.com/anthropic")
        .apiKey(apiKey)
        .build()

    private val model = "deepseek-v4-pro"
    private val toolExecutor = ToolExecutor(project, session)

    // ── 回调 ──
    var onToken: ((String) -> Unit)? = null
    var onReasoningContent: ((String) -> Unit)? = null
    var onToolCall: ((toolUseId: String, toolName: String, params: Map<String, Any?>) -> Unit)? =
        null
    var onToolCallStateChanged: ((toolUseId: String, state: ToolCallState, result: String?, durationMs: Long?) -> Unit)? =
        null
    var onTurnCompleted: (() -> Unit)? = null

    init {
        toolExecutor.onToolStateChanged = { toolUseId, state, result, durationMs ->
            onToolCallStateChanged?.invoke(toolUseId, state, result, durationMs)
        }
    }

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

        session.startProcessing()
        val output = StringBuilder()
        val reasoningOutput = StringBuilder()
        var turn = 0
        var lastStopReason = ""
        var retryCount = 0

        try {
            while (!session.cancelled) {
                val turnOutput = StringBuilder()
                val accumulator = BetaMessageAccumulator.create()

                try {
                    client.beta().messages().createStreaming(builder.build()).use { stream ->
                        stream.stream().forEach { event ->
                            accumulator.accumulate(event)

                            // reasoning_content（思考过程）
                            if (event.isContentBlockDelta()) {
                                val delta = event.asContentBlockDelta()
                                val contentDelta = delta.delta()
                                // 优先处理 reasoning_content
                                contentDelta.thinking().ifPresent { thinking ->
                                    reasoningOutput.append(thinking.thinking())
                                    onReasoningContent?.invoke(thinking.thinking())
                                }
                                // 再处理普通文本
                                contentDelta.text().ifPresent { text ->
                                    turnOutput.append(text.text())
                                    onToken?.invoke(text.text())
                                }
                            } else if (event.isContentBlockStart()) {
                                val start = event.asContentBlockStart()
                                start.contentBlock().toolUse().ifPresent { toolUse ->
                                    onToolCall?.invoke(
                                        toolUse.id(),
                                        toolUse.name(),
                                        ToolInput.map(toolUse._input())
                                    )
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
                    val toolUses =
                        assistantMessage.content().mapNotNull { it.toolUse().orElse(null) }
                    lastStopReason =
                        assistantMessage.stopReason().map { it.toString() }.orElse(lastStopReason)

                    // end_turn 且无工具调用 → LLM 自然结束
                    if (lastStopReason.contains("end_turn") && toolUses.isEmpty()) break
                    if (toolUses.isEmpty()) break

                    // 执行工具调用
                    session.startExecuting()
                    val toolResultParams = mutableListOf<BetaContentBlockParam>()

                    for (toolUse in toolUses) {
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

                    session.doneExecuting()

                    // 将 assistant 消息 + tool 结果回传给 LLM
                    builder
                        .addMessage(assistantMessage)
                        .addUserMessageOfBetaContentBlockParams(toolResultParams)

                    turn++
                    retryCount = 0 // 成功一轮，重置重试计数
                } catch (e: Exception) {
                    // 保留已接收文本
                    output.append(turnOutput)

                    when {
                        // 429 Rate Limit — 自动重试
                        e is com.anthropic.errors.RateLimitException -> {
                            if (retryCount < 2) {
                                retryCount++
                                val waitSec = try {
                                    e.message?.let { msg ->
                                        Regex("(\\d+)\\s*(?:seconds|秒)").find(msg)?.groupValues?.get(
                                            1
                                        )
                                            ?.toLongOrNull()
                                    } ?: 10L
                                } catch (_: Exception) {
                                    10L
                                }
                                Thread.sleep(waitSec * 1000)
                                continue // 重试当前 turn（不增加 turn 计数）
                            } else {
                                session.markError("429 Rate Limit — 已重试 2 次仍失败")
                                return Result.Error("Rate Limit 重试耗尽")
                            }
                        }
                        // 流中断 — 保留已接收文本，用户可重试
                        e is SocketTimeoutException || e is java.io.IOException -> {
                            session.markError("连接中断: ${e.message}")
                            return Result.Error(
                                "连接中断: ${e.message}\n已接收文本: ${
                                    output.take(
                                        500
                                    )
                                }"
                            )
                        }
                        // 其他异常
                        else -> {
                            session.markError(e.message ?: "Unknown error")
                            return Result.Error(e.message ?: "Unknown error")
                        }
                    }
                }
            }

            session.finishTurn()
            val fullReasoning = reasoningOutput.toString()
            val fullText = output.toString()
            return Result.Success(
                text = fullText,
                reasoning = fullReasoning.ifEmpty { null },
                turns = turn,
                stopReason = lastStopReason
            )

        } catch (e: Exception) {
            session.markError(e.message ?: "Unknown error")
            return Result.Error(e.message ?: "Unknown error")
        }
    }

    // ── System Prompt（对齐 tech-spec.md 8.1 节） ──
    private fun buildSystemPrompt(): String {
        val skillExt = com.aiassistant.skills.SkillManager(project).getSystemPromptExtension()
        val toolSection = ToolRegistry.buildSystemPromptTools()
        val projectName = project.name
        val basePath = project.basePath ?: "unknown"
        val currentFile = try {
            val editor =
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
            editor?.virtualFile?.name ?: "无"
        } catch (_: Exception) {
            "无"
        }

        val dateStr = java.time.LocalDate.now().toString()
        val osName = System.getProperty("os.name")
        val branch = try {
            val bp = project.basePath
            if (bp != null) {
                val gitDir = File(bp, ".git")
                if (gitDir.exists()) {
                    val headRef = File(gitDir, "HEAD").readText().trim()
                    headRef.removePrefix("ref: refs/heads/")
                } else "unknown"
            } else "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        // 检测编码规范文件
        val configFiles = listOf("CLAUDE.md", "CODEX.md", "AGENTS.md", ".cursorrules")
            .filter { project.basePath?.let { bp -> File(bp, it).exists() } ?: false }
        val configRef = when {
            configFiles.isNotEmpty() -> "遵守项目中 ${configFiles.joinToString(" / ")} 定义的编码规范"
            else -> ""
        }

        return """
你是 Code Assistant，一个运行在 JetBrains IDE 中的智能编程助手。你可以：
- 阅读项目中的任何文件
- 修改文件内容（精确替换或完整覆盖）
- 执行 Shell 命令
- 列出目录结构
- 搜索代码内容
- 读取 IDE 诊断信息（错误和警告）
- 启动子代理处理子任务

当前项目：$projectName
项目路径：$basePath
当前文件：$currentFile

## 工具使用原则

1. 先用 readFile 或 listFiles 获取足够信息，再使用 writeFile/editFile 修改代码。
2. 修改代码前，先读取目标文件的完整内容或足够上下文。
3. editFile 的 oldString 必须在文件中唯一且精确匹配。如果不确定 oldString，先用 readFile 读取目标区域。
4. Shell 命令的工作目录默认为项目根目录。长时间运行的命令（如 gradle build）是正常的，不需要手动终止。
5. 所有文件路径使用项目内相对路径。

## 回复风格

- 使用中文回复
- 代码块使用正确的语言标记（```kotlin、```java、```json 等）
- 修改文件前简要说明变更内容
- 执行 Shell 命令前说明命令用途

$toolSection

## 环境
- 日期: $dateStr
- 操作系统: $osName
- 工作目录: $basePath
- 项目名: $projectName
- Git 分支: $branch

$configRef
$skillExt""".trimIndent()
    }

    sealed class Result {
        data class Success(
            val text: String,
            val reasoning: String? = null,
            val turns: Int,
            val stopReason: String
        ) : Result()

        data class Error(val message: String) : Result()
    }
}
