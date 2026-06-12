package com.aiassistant.agent_v3

import com.aiassistant.*
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
            "git_diff", "git_log", "git_status", "web_search",
            "web_fetch", "task", "ask_user"
        )

        /** create_plan 元工具的 input_schema（含嵌套 items，ToolParameter 无法表达） */
        private const val CREATE_PLAN_SCHEMA = """{"type":"object","properties":{"title":{"type":"string","description":"计划标题"},"steps":{"type":"array","items":{"type":"object","properties":{"description":{"type":"string","description":"步骤描述"}},"required":["description"]}}},"required":["title","steps"],"additionalProperties":false}"""

        private const val CREATE_PLAN_TOOL_JSON = """{"name":"create_plan","description":"为复杂任务创建执行计划。简单任务不要调用。","input_schema":$CREATE_PLAN_SCHEMA}"""

        private const val UPDATE_PLAN_STEP_SCHEMA = """{"type":"object","properties":{"index":{"type":"integer","description":"步骤序号（从1开始）"},"status":{"type":"string","enum":["in_progress","done","failed"],"description":"新状态"},"result":{"type":"string","description":"可选的结果摘要（done/failed 时使用）"}},"required":["index","status"]}"""

        private const val UPDATE_PLAN_STEP_TOOL_JSON = """{"name":"update_plan_step","description":"更新执行计划中的步骤状态。在开始、完成或失败时调用。","input_schema":$UPDATE_PLAN_STEP_SCHEMA}"""
    }

    val ctx = AgentContext(project)
    @Volatile private var cancelled = false
    private var model: String = AppSettingsService.getInstance().getModel()

    /** 当前正在等待用户确认的 latch（用于 stop() 时解除阻塞，避免背景线程挂起）。 */
    @Volatile private var pendingConfirmLatch: CountDownLatch? = null

    var onMessage: ((AgentMessage) -> Unit)? = null
    var onStreaming: ((String) -> Unit)? = null
    var onThinking: ((String?) -> Unit)? = null
    var onToolExecute: ((String, String) -> Unit)? = null
    var onToolResult: ((String, String) -> Unit)? = null
    var onPlanUpdate: ((AgentContext.Plan) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChange: ((Boolean) -> Unit)? = null
    var onModelRouted: ((String) -> Unit)? = null
    /** 思考过程实时流式回调 — 每个 ThinkingDelta 触发，参数为累积的思考文本 */
    var onThinkingDelta: ((String) -> Unit)? = null
    /** 工具确认回调 — UI 层实现内联确认，通过 latch 通知结果 */
    var onConfirmTool: ((String, String, CountDownLatch, AtomicBoolean) -> Unit)? = null

    fun initialize(mcpTools: List<AgentTool> = emptyList()) {
        ctx.toolRegistry.registerBuiltIn()
        ctx.toolRegistry.registerMcp(mcpTools)
        val basePath = project.basePath
        val skillTools = if (basePath != null) SkillEngine.loadProjectSkills(basePath) else emptyList()
        ctx.toolRegistry.registerSkills(skillTools)
        ctx.systemPrompt = buildSystemPrompt(skillTools)
    }

    fun run(userMessage: String, apiKey: String, callback: (String, String) -> Unit) {
        cancelled = false
        onStateChange?.invoke(true)
        val history = mutableListOf<AnthropicMessage>()

        Thread {
            try {
                // 诊断信息
                val toolCount = ctx.toolRegistry.getAll().size
                val toolNames = ctx.toolRegistry.getAll().joinToString(", ") { it.name }
                edt { onMessage?.invoke(AgentMessage("system", "$toolCount 个工具已就绪: $toolNames")) }

                history.add(AnthropicMessage("user", userMessage))

                var loopCount = 0
                var consecutiveFailures = 0

                while (loopCount < MAX_LOOPS && !cancelled) {
                    edt { onThinking?.invoke("思考中...") }

                    val result = callAnthropic(apiKey, history)
                    if (result == null) {
                        edt { onError?.invoke("API 调用失败") }
                        break
                    }

                    val (textContent, thinking, toolCalls) = result
                    edt { onThinking?.invoke(null) }

                    if (toolCalls.isNotEmpty()) {
                        // 工具调用轮：thinking + 部分文本 先固化为消息，让用户在工具执行期间可查阅
                        if (thinking.isNotEmpty()) {
                            edt { onMessage?.invoke(AgentMessage("thinking", thinking)) }
                        }
                        if (textContent.isNotEmpty()) {
                            edt {
                                onMessage?.invoke(AgentMessage("assistant", textContent))
                                onStreaming?.invoke("")
                            }
                        }
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

                            // create_plan 元工具：LLM 自主决定是否创建执行计划
                            if (tc.name == "create_plan") {
                                val plan = parsePlanFromArgs(tc.arguments)
                                ctx.currentPlan = plan
                                edt { onPlanUpdate?.invoke(plan) }
                                val planResult = "计划已创建，共${plan.steps.size}步。请从第一步开始执行。"
                                history.add(AnthropicMessage(
                                    "assistant", textContent, toolUseId = tc.id,
                                    toolName = tc.name, toolInput = tc.arguments
                                ))
                                history.add(AnthropicMessage(
                                    "user", planResult, toolCallId = tc.id
                                ))
                                edt { onToolResult?.invoke(tc.name, planResult) }
                                continue
                            }

                            // update_plan_step 元工具：LLM 更新计划步骤状态
                            if (tc.name == "update_plan_step") {
                                val stepIndex = Regex(""""index"\s*:\s*(\d+)""").find(tc.arguments)?.groupValues?.get(1)?.toIntOrNull()
                                val status = Regex(""""status"\s*:\s*"([^"]*)"""").find(tc.arguments)?.groupValues?.get(1)
                                val result = Regex(""""result"\s*:\s*"([^"]*)"""").find(tc.arguments)?.groupValues?.get(1)
                                if (stepIndex != null && status != null) {
                                    val stepStatus = when (status) {
                                        "in_progress" -> AgentContext.StepStatus.IN_PROGRESS
                                        "done" -> AgentContext.StepStatus.DONE
                                        "failed" -> AgentContext.StepStatus.FAILED
                                        else -> null
                                    }
                                    if (stepStatus != null) {
                                        ctx.currentPlan?.updateStep(stepIndex, stepStatus, result)
                                        ctx.currentPlan?.let { edt { onPlanUpdate?.invoke(it) } }
                                    }
                                }
                                val msg = "步骤 $stepIndex 状态更新为 $status"
                                history.add(AnthropicMessage("assistant", textContent, toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments))
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            val params = parseParams(tc.arguments)
                            // skill 工具不执行实际文件操作，只是 prompt 注入包装器，无需审批
                            val isSkillTool = ctx.toolRegistry.isSkill(tc.name)
                            val approved = if (isSkillTool || tc.name in SAFE_TOOLS || tc.name in AppSettingsService.getInstance().getToolWhitelist()) {
                                true
                            } else {
                                // 内联确认：通过回调 + CountDownLatch 等待用户操作。
                                // 持有 latch 引用，使 stop() 能解除等待；带超时兜底防永久挂起。
                                val latch = CountDownLatch(1)
                                val userChoice = AtomicBoolean(false)
                                pendingConfirmLatch = latch
                                onConfirmTool?.invoke(tc.name, tc.arguments, latch, userChoice)
                                try {
                                    latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                } finally {
                                    pendingConfirmLatch = null
                                }
                                // 等待期间被取消 → 视为未授权，跳出
                                if (cancelled) break
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
                        callback(textContent, thinking)
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

    fun stop() {
        cancelled = true
        sseClient.cancel()
        // 解除可能正卡在用户确认上的背景线程，避免挂起到超时
        pendingConfirmLatch?.countDown()
    }

    // ---- Anthropic API ----

    data class ToolCallResult(val id: String, val name: String, val arguments: String)

    private fun callAnthropic(
        apiKey: String, history: List<AnthropicMessage>
    ): Triple<String, String, List<ToolCallResult>>? {
        val toolsJson = buildToolsJsonWithPlan()
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

        // SSE 事件序号（单次 callAnthropic 内自增），用于追踪 content_block 顺序
        var sseSeq = 0
        sseClient.connect(
            url = adapter.endpoint, apiKey = apiKey, requestBody = requestBody,
            callback = object : SseCallback {
                override fun onData(data: String) {
                    val lines = data.split("\n")
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val event = adapter.parseSseEvent(trimmed) ?: continue
                        sseSeq++
                        val evName = event::class.simpleName ?: "?"
                        when (event) {
                            is ParsedEvent.TextDelta -> {
                                hasResponse = true
                                textBuffer.append(event.text)
                                AppLogger.info("SSE#$sseSeq $evName text(${event.text.length}c) totalText=${textBuffer.length} → edt:onStreaming")
                                edt { onStreaming?.invoke(textBuffer.toString()) }
                            }
                            is ParsedEvent.ToolUseStart -> {
                                hasResponse = true
                                inToolUse = true
                                currentToolId = event.id
                                currentToolName = event.name
                                currentToolInput.clear()
                                AppLogger.info("SSE#$sseSeq $evName id=${event.id} name=${event.name}")
                            }
                            is ParsedEvent.InputJsonDelta -> {
                                currentToolInput.append(event.partial)
                            }
                            is ParsedEvent.ContentBlockStop -> {
                                AppLogger.info("SSE#$sseSeq $evName inToolUse=$inToolUse")
                                if (inToolUse) {
                                    AppLogger.info("SSE#$sseSeq toolCall: id=$currentToolId name=$currentToolName")
                                    toolCalls.add(ToolCallResult(
                                        currentToolId, currentToolName,
                                        currentToolInput.toString().ifEmpty { "{}" }
                                    ))
                                    inToolUse = false
                                }
                            }
                            is ParsedEvent.MessageStart -> { hasResponse = true; AppLogger.info("SSE#$sseSeq $evName") }
                            is ParsedEvent.ThinkingStart -> { hasResponse = true; AppLogger.info("SSE#$sseSeq $evName") }
                            is ParsedEvent.ThinkingDelta -> {
                                thinkingBuffer.append(event.thinking)
                                AppLogger.info("SSE#$sseSeq $evName thinking(${event.thinking.length}c) totalThinking=${thinkingBuffer.length} → edt:onThinkingDelta")
                                edt { onThinkingDelta?.invoke(thinkingBuffer.toString()) }
                            }
                            is ParsedEvent.SignatureDelta -> {}
                            is ParsedEvent.MessageStop -> {
                                AppLogger.info("SSE#$sseSeq $evName → done.notifyAll")
                                synchronized(done) { done.notifyAll() }
                            }
                            else -> { AppLogger.info("SSE#$sseSeq $evName (unhandled)") }
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
            AppLogger.requestFailed(-1, detail)
            edt { onError?.invoke("API 调用失败: $detail") }
            return null
        }

        val finalText = textBuffer.toString()
        val thinking = thinkingBuffer.toString()

        AppLogger.info("callAnthropic 返回: finalText.length=${finalText.length} thinking.length=${thinking.length} toolCalls.size=${toolCalls.size}")

        // DeepSeek V4 行为：简单回复时可能把全部内容放进 thinking、正式 text 为空。
        // 若这是最终回复轮（无工具调用）且 text 为空但 thinking 非空，
        // 则把 thinking 当作正式回复返回（正常气泡显示），而不是折叠成"思考过程"后丢失结果。
        if (toolCalls.isEmpty() && finalText.isEmpty() && thinking.isNotEmpty()) {
            AppLogger.info("callAnthropic: thinking 降级为 text（DeepSeek V4 behavior）")
            return Triple(thinking, "", toolCalls)
        }

        // thinking 随返回值一起出去，由 run() 统一 dispatch，避免 callAnthropic 内部
        // 单独 dispatch 导致 ChatViewModel 在 streamingContent 未清空时 rebuild（重复渲染同一份回复文本）。
        return Triple(finalText, thinking, toolCalls)
    }

    private fun parseParams(json: String): Map<String, String> {
        val p = mutableMapOf<String, String>()
        Regex(""""(\w+)"\s*:\s*("[^"]*"|\d+|true|false|null)""").findAll(json).forEach { m ->
            p[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return p
    }

    private fun buildSystemPrompt(skills: List<AgentTool> = emptyList()): String {
        val tools = ctx.toolRegistry.getAll()
        val builtInCount = ctx.toolRegistry.getAll().count { it !is SkillTool }
        val toolList = tools.take(builtInCount).joinToString("\n") { t ->
            val params = t.parameters.joinToString(", ") { "${it.name}:${it.type}" }
            "- **${t.name}**($params): ${t.description}"
        }
        val skillsSection = if (skills.isNotEmpty()) {
            "\n## Skills\n" + skills.joinToString("\n\n") { s ->
                val st = s as? SkillTool ?: return@joinToString ""
                "### ${st.name}\n${st.description}\n\n${st.prompt}"
            }
        } else ""
        return """
You are an AI coding assistant in PhpStorm. Use tools to work with the project.

## Project: ${project.name}
Path: ${project.basePath ?: "unknown"}

## Tools
$toolList

## Planning
对于需要多步骤完成的复杂任务（如实现功能、重构代码、架构变更），你应该先调用 **create_plan** 工具创建执行计划。
简单任务（读取文件、回答单个问题、一行修改）不要创建计划。
创建计划后，每个步骤开始前调用 **update_plan_step**（status="in_progress"），完成后调用（status="done" + result 摘要），失败时调用（status="failed" + result 原因）。
$skillsSection
## Rules
- Use tools to get real data; never guess
- Read before edit; report results in Chinese
- For git: use git_status/git_diff/git_log
- For search: use search_code (not execute_command grep)
- For commands: use execute_command
        """.trimIndent()
    }

    /** 将 create_plan 元工具拼接到 tools JSON 末尾（ToolRegistryV3 的 ToolParameter 无法表达嵌套 items 子结构） */
    private fun buildToolsJsonWithPlan(): String {
        val base = ctx.toolRegistry.buildToolsJson()  // "[...]"（已缓存，含 JsonUtils.escapeJson 保护）
        val inner = base.removeSurrounding("[", "]")
        val all = listOfNotNull(inner.takeIf { it.isNotBlank() }, CREATE_PLAN_TOOL_JSON, UPDATE_PLAN_STEP_TOOL_JSON).joinToString(",")
        return "[$all]"
    }

    /** 从 create_plan 的 JSON 参数中解析 Plan */
    private fun parsePlanFromArgs(args: String): AgentContext.Plan {
        val title = Regex(""""title"\s*:\s*"([^"]*)"""").find(args)?.groupValues?.get(1) ?: "执行计划"
        AppLogger.info("parsePlanFromArgs title=$title args=${args.take(200)}")
        val stepsJson = Regex(""""steps"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(args)?.groupValues?.get(1) ?: ""
        val stepDescs = Regex(""""description"\s*:\s*"([^"]*)"""").findAll(stepsJson).map { it.groupValues[1] }.toList()
        val steps = if (stepDescs.isNotEmpty()) {
            stepDescs.mapIndexed { i, desc -> AgentContext.Step(index = i + 1, description = desc) }
        } else {
            listOf(
                AgentContext.Step(1, "分析需求和现有代码"),
                AgentContext.Step(2, "制定实现方案"),
                AgentContext.Step(3, "逐步实现并测试"),
                AgentContext.Step(4, "验证结果并总结")
            )
        }
        return AgentContext.Plan(title = title, steps = steps)
    }

    private fun edt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
