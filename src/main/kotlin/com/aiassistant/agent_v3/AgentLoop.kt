package com.aiassistant.agent_v3

import com.aiassistant.*
import com.aiassistant.agent.ModelRouter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class AgentLoop(
    private val project: Project,
    private val sseClient: SseClient = SseClient(),
    private val adapter: AnthropicAdapter = AnthropicAdapter()
) {
    companion object {
        const val MAX_LOOPS = 100
        const val MAX_FAILURES = 3

        /** 安全工具白名单 — 无需用户确认直接执行 */
        val SAFE_TOOLS = setOf(
            "search_code", "read_file", "list_directory",
            "git_diff", "git_log", "git_status"
        )
    }

    val ctx = AgentContext(project)
    private var cancelled = false
    private var model: String = AppSettingsService.getInstance().getModel()

    var onMessage: ((AgentMessage) -> Unit)? = null
    var onStreaming: ((String) -> Unit)? = null
    var onThinking: ((String?) -> Unit)? = null
    var onToolExecute: ((String, String) -> Unit)? = null
    var onToolResult: ((String, String) -> Unit)? = null
    var onPlanUpdate: ((AgentContext.Plan) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChange: ((Boolean) -> Unit)? = null
    var onModelRouted: ((String) -> Unit)? = null
    /** 工具确认回调 — UI 层实现内联确认，通过 latch 通知结果 */
    var onConfirmTool: ((String, String, CountDownLatch, AtomicBoolean) -> Unit)? = null

    fun initialize(mcpTools: List<AgentTool> = emptyList()) {
        ctx.toolRegistry.registerBuiltIn()
        ctx.toolRegistry.registerMcp(mcpTools)
        val basePath = project.basePath
        if (basePath != null) {
            val skillTools = SkillEngine.loadProjectSkills(basePath)
            ctx.toolRegistry.registerSkills(skillTools)
        }
        ctx.systemPrompt = buildSystemPrompt()
    }

    fun run(userMessage: String, apiKey: String, callback: (String) -> Unit) {
        cancelled = false
        onStateChange?.invoke(true)
        val history = mutableListOf<AnthropicMessage>()

        Thread {
            try {
                // 诊断信息
                val toolCount = ctx.toolRegistry.getAll().size
                val toolNames = ctx.toolRegistry.getAll().joinToString(", ") { it.name }
                edt { onMessage?.invoke(AgentMessage("system", "$toolCount 个工具已就绪: $toolNames")) }

                // Plan mode
                if (Planner.shouldPlan(userMessage)) {
                    val plan = Planner.generatePlan(userMessage)
                    ctx.currentPlan = plan
                    edt { onPlanUpdate?.invoke(plan) }
                    edt { onMessage?.invoke(AgentMessage("assistant", Planner.buildPlanSummary(plan))) }
                }

                history.add(AnthropicMessage("user", userMessage))
                model = ModelRouter.selectModel(userMessage)
                edt { onModelRouted?.invoke(model) }

                var loopCount = 0
                var consecutiveFailures = 0

                while (loopCount < MAX_LOOPS && !cancelled) {
                    edt { onThinking?.invoke("思考中...") }

                    val result = callAnthropic(apiKey, history)
                    if (result == null) {
                        edt { onError?.invoke("API 调用失败") }
                        break
                    }

                    val (textContent, toolCalls) = result
                    edt { onThinking?.invoke(null) }

                    if (toolCalls.isNotEmpty()) {
                        consecutiveFailures = 0

                        for (tc in toolCalls) {
                            if (cancelled) break

                            try {
                                SwingUtilities.invokeAndWait {
                                    onToolExecute?.invoke(tc.name, tc.arguments)
                                    onThinking?.invoke("执行 ${tc.name}...")
                                }
                            } catch (_: Exception) {
                                edt { onToolExecute?.invoke(tc.name, tc.arguments) }
                            }

                            val params = parseParams(tc.arguments)
                            val approved = if (tc.name in SAFE_TOOLS || tc.name in AppSettingsService.getInstance().getToolWhitelist()) {
                                true
                            } else {
                                // 内联确认：通过回调 + CountDownLatch 等待用户操作
                                val latch = CountDownLatch(1)
                                val userChoice = AtomicBoolean(false)
                                onConfirmTool?.invoke(tc.name, tc.arguments, latch, userChoice)
                                try { latch.await() } catch (_: InterruptedException) {}
                                userChoice.get()
                            }
                            val toolResult = if (approved) {
                                val r = ctx.toolRegistry.executeTool(tc.name, params, project)
                                if (!r.success) consecutiveFailures++ else consecutiveFailures = 0
                                r
                            } else {
                                consecutiveFailures++
                                ToolResult(false, "", "用户未授权执行 ${tc.name}，请绕开此操作或向用户说明")
                            }
                            val resultText = if (toolResult.success) toolResult.content else "错误: ${toolResult.error}"

                            history.add(AnthropicMessage(
                                "assistant", textContent, toolUseId = tc.id,
                                toolName = tc.name, toolInput = tc.arguments
                            ))
                            history.add(AnthropicMessage(
                                "user", resultText, toolCallId = tc.id
                            ))

                            try {
                                SwingUtilities.invokeAndWait {
                                    onToolResult?.invoke(tc.name, resultText)
                                    onThinking?.invoke(null)
                                }
                            } catch (_: Exception) {
                                edt { onToolResult?.invoke(tc.name, resultText) }
                            }
                        }

                        if (consecutiveFailures >= MAX_FAILURES) break
                    } else {
                        callback(textContent)
                        break
                    }

                    loopCount++
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                edt { onError?.invoke("Agent 错误: ${e.message}") }
            } finally {
                onStateChange?.invoke(false)
                edt { onThinking?.invoke(null) }
            }
        }.start()
    }

    fun stop() { cancelled = true; sseClient.cancel() }

    // ---- Anthropic API ----

    data class ToolCallResult(val id: String, val name: String, val arguments: String)

    private fun callAnthropic(
        apiKey: String, history: List<AnthropicMessage>
    ): Pair<String, List<ToolCallResult>>? {
        val toolsJson = ctx.toolRegistry.buildToolsJson()
        val requestBody = adapter.buildRequest(ctx.systemPrompt, history, toolsJson, modelOverride = model)

        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        val toolCalls = mutableListOf<ToolCallResult>()
        var currentToolId = ""
        var currentToolName = ""
        val currentToolInput = StringBuilder()
        var inToolUse = false
        val done = Object()
        var hasResponse = false
        var errorDetail: String? = null

        sseClient.connect(
            url = adapter.endpoint, apiKey = apiKey, requestBody = requestBody,
            callback = object : SseCallback {
                override fun onData(data: String) {
                    val lines = data.split("\n")
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val event = adapter.parseSseEvent(trimmed) ?: continue
                        when (event) {
                            is ParsedEvent.TextDelta -> {
                                hasResponse = true
                                textBuffer.append(event.text)
                                edt { onStreaming?.invoke(textBuffer.toString()) }
                            }
                            is ParsedEvent.ToolUseStart -> {
                                hasResponse = true
                                inToolUse = true
                                currentToolId = event.id
                                currentToolName = event.name
                                currentToolInput.clear()
                            }
                            is ParsedEvent.InputJsonDelta -> {
                                currentToolInput.append(event.partial)
                            }
                            is ParsedEvent.ContentBlockStop -> {
                                if (inToolUse) {
                                    toolCalls.add(ToolCallResult(
                                        currentToolId, currentToolName,
                                        currentToolInput.toString().ifEmpty { "{}" }
                                    ))
                                    inToolUse = false
                                }
                            }
                            is ParsedEvent.MessageStart -> { hasResponse = true }
                            is ParsedEvent.ThinkingStart -> { hasResponse = true }
                            is ParsedEvent.ThinkingDelta -> {
                                thinkingBuffer.append(event.thinking)
                            }
                            is ParsedEvent.SignatureDelta -> {}
                            is ParsedEvent.MessageStop -> {
                                synchronized(done) { done.notifyAll() }
                            }
                            else -> {}
                        }
                    }
                }
                override fun onDone() = synchronized(done) { done.notifyAll() }
                override fun onError(code: Int, msg: String) {
                    errorDetail = "HTTP $code: ${msg.take(200)}"
                    synchronized(done) { done.notifyAll() }
                }
            }
        )

        synchronized(done) { try { done.wait(120_000) } catch (_: InterruptedException) {} }
        if (!hasResponse) {
            val detail = errorDetail ?: "无响应 — 请检查 API Key 和网络连接"
            edt { onError?.invoke("API 调用失败: $detail") }
            return null
        }

        // 输出思考过程到对话
        if (thinkingBuffer.isNotEmpty()) {
            edt { onMessage?.invoke(AgentMessage("thinking", thinkingBuffer.toString())) }
        }

        return Pair(textBuffer.toString(), toolCalls)
    }

    private fun parseParams(json: String): Map<String, String> {
        val p = mutableMapOf<String, String>()
        Regex(""""(\w+)"\s*:\s*("[^"]*"|\d+|true|false|null)""").findAll(json).forEach { m ->
            p[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return p
    }

    private fun buildSystemPrompt(): String {
        val tools = ctx.toolRegistry.getAll()
        val toolList = tools.joinToString("\n") { t ->
            val params = t.parameters.joinToString(", ") { "${it.name}:${it.type}" }
            "- **${t.name}**($params): ${t.description}"
        }
        return """
You are an AI coding assistant in PhpStorm. Use tools to work with the project.

## Project: ${project.name}
Path: ${project.basePath ?: "unknown"}

## Tools
$toolList

## Rules
- Use tools to get real data; never guess
- Read before edit; report results in Chinese
- For git: use git_status/git_diff/git_log
- For search: use search_code (not execute_command grep)
- For commands: use execute_command
        """.trimIndent()
    }

    private fun edt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
