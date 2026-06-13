package com.aiassistant.agent

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
            "web_fetch", "task", "ask_user", "code_intelligence"
        )

        /** create_plan 元工具的 input_schema（含嵌套 items，ToolParameter 无法表达） */
        private const val CREATE_PLAN_SCHEMA = """{"type":"object","properties":{"title":{"type":"string","description":"计划标题"},"steps":{"type":"array","items":{"type":"object","properties":{"subject":{"type":"string","description":"子任务简短名称（显示在任务列表）"},"description":{"type":"string","description":"子任务详细描述（可选）"}},"required":["subject"]}}},"required":["title","steps"],"additionalProperties":false}"""

        private const val CREATE_PLAN_TOOL_JSON = """{"name":"create_plan","description":"为复杂任务创建执行计划。简单任务不要调用。","input_schema":$CREATE_PLAN_SCHEMA}"""

        private const val UPDATE_PLAN_STEP_SCHEMA = """{"type":"object","properties":{"index":{"type":"integer","description":"步骤序号（从1开始）"},"status":{"type":"string","enum":["in_progress","done","failed"],"description":"新状态"},"result":{"type":"string","description":"可选的结果摘要（done/failed 时使用）"}},"required":["index","status"]}"""

        private const val UPDATE_PLAN_STEP_TOOL_JSON = """{"name":"update_plan_step","description":"更新执行计划中的步骤状态。在开始、完成或失败时调用。","input_schema":$UPDATE_PLAN_STEP_SCHEMA}"""
    }

    val ctx = AgentContext(project)
    @Volatile private var cancelled = false
    @Volatile private var model: String = AppSettingsService.getInstance().getModel()

    /** 刷新模型配置，用于 Settings 变更后同步 */
    fun refreshModel() {
        model = AppSettingsService.getInstance().getModel()
    }

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

    fun run(userMessage: String, apiKey: String, images: List<ImageData>? = null, callback: (String, String) -> Unit) {
        cancelled = false
        onStateChange?.invoke(true)
        val history = mutableListOf<AnthropicMessage>()

        Thread {
            try {
                // 诊断信息
                val toolCount = ctx.toolRegistry.getAll().size
                val toolNames = ctx.toolRegistry.getAll().joinToString(", ") { it.name }
                edt { onMessage?.invoke(AgentMessage("system", "$toolCount 个工具已就绪: $toolNames")) }

                history.add(AnthropicMessage("user", userMessage, images = images))

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
                                // 先清空流式状态，再添加消息。
                                // 如果顺序反过来，onMessage 触发 rebuildConversation 时
                                // streamingContent 尚未清空，会导致同一个 AI 回复同时出现在
                                // 流式气泡和正式消息气泡中（两份重复渲染）。
                                onStreaming?.invoke("")
                                onMessage?.invoke(AgentMessage("assistant", textContent))
                            }
                        }
                        consecutiveFailures = 0

                        // 标记：只有第一个 toolCall 才将 textContent 添加到 history，后续 toolCall 跳过
                        var firstToolCallTextAdded = false

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
                                val newPlan = parsePlanFromArgs(tc.arguments)
                                val existingPlan = ctx.currentPlan
                                val planResult = if (existingPlan != null) {
                                    // 已有计划 → 追加步骤到末尾
                                    existingPlan.appendSteps(newPlan.stepsSnapshot())
                                    edt { onPlanUpdate?.invoke(existingPlan) }
                                    "已将 ${newPlan.stepsSnapshot().size} 个新步骤追加到当前计划（共 ${existingPlan.stepsSnapshot().size} 步）"
                                } else {
                                    // 新计划
                                    ctx.currentPlan = newPlan
                                    edt { onPlanUpdate?.invoke(newPlan) }
                                    "计划已创建，共 ${newPlan.stepsSnapshot().size} 步。请从第一步开始执行。"
                                }
                                if (!firstToolCallTextAdded) {
                                    history.add(AnthropicMessage(
                                        "assistant", textContent, toolUseId = tc.id,
                                        toolName = tc.name, toolInput = tc.arguments
                                    ))
                                    firstToolCallTextAdded = true
                                }
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
                                var updated = false
                                if (stepIndex != null && status != null) {
                                    val stepStatus = when (status) {
                                        "in_progress" -> AgentContext.StepStatus.IN_PROGRESS
                                        "done" -> AgentContext.StepStatus.DONE
                                        "failed" -> AgentContext.StepStatus.FAILED
                                        else -> null
                                    }
                                    if (stepStatus != null) {
                                        updated = ctx.currentPlan?.updateStep(stepIndex, stepStatus, result) ?: false
                                        if (updated) {
                                            ctx.currentPlan?.let { edt { onPlanUpdate?.invoke(it) } }
                                        }
                                    }
                                }
                                val msg = if (updated) "步骤 $stepIndex 状态更新为 $status" else "更新失败: 步骤 $stepIndex 不存在，当前计划共 ${ctx.currentPlan?.stepsSnapshot()?.size ?: 0} 步"
                                if (!firstToolCallTextAdded) {
                                    history.add(AnthropicMessage("assistant", textContent, toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            val params = parseParams(tc.arguments)
                            // skill 工具不执行实际文件操作，只是 prompt 注入包装器，无需审批
                            val isSkillTool = ctx.toolRegistry.isSkill(tc.name)
                            // read_file 特殊处理：仅在项目目录内自动放行，跨目录需用户确认
                            val readFileOutside = tc.name == "read_file" && params["path"] != null &&
                                !com.aiassistant.shared.PathUtils.isInsideProject(params["path"]!!, project.basePath)
                            val approved = if (!readFileOutside && (isSkillTool || tc.name in SAFE_TOOLS || tc.name in AppSettingsService.getInstance().getToolWhitelist())) {
                                true
                            } else {
                                // 内联确认：通过回调 + CountDownLatch 等待用户操作。
                                // 持有 latch 引用，使 stop() 能解除等待；带超时兜底防永久挂起。
                                val latch = CountDownLatch(1)
                                val userChoice = AtomicBoolean(false)
                                pendingConfirmLatch = latch
                                // 二次检查：防止 stop() 在赋值前被调用导致 countDown 空操作
                                if (cancelled) latch.countDown()
                                onConfirmTool?.invoke(tc.name, tc.arguments, latch, userChoice)
                                try {
                                    val confirmed = latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
                                    if (!confirmed) {
                                        // 超时：用户未在 10 分钟内操作，结束本轮对话
                                        consecutiveFailures = MAX_FAILURES
                                        break
                                    }
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
                                if (readFileOutside) {
                                    ToolResult(false, "", "文件不存在: ${params["path"]}")
                                } else {
                                    ToolResult(false, "", "用户未授权执行 ${tc.name}，请绕开此操作或向用户说明")
                                }
                            }
                            val resultText = if (toolResult.success) toolResult.content else "错误: ${toolResult.error}"

                            if (!firstToolCallTextAdded) {
                                history.add(AnthropicMessage(
                                    "assistant", textContent, toolUseId = tc.id,
                                    toolName = tc.name, toolInput = tc.arguments
                                ))
                                firstToolCallTextAdded = true
                            }
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
                AppLogger.error("AgentLoop 异常: ${e.message}\n${e.stackTraceToString()}")
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
        val planPrompt = buildPlanPrompt() // 动态注入当前计划状态
        val effectivePrompt = if (planPrompt.isNotEmpty()) ctx.systemPrompt + "\n\n" + planPrompt else ctx.systemPrompt
        val toolsJson = buildToolsJsonWithPlan()
        val thinkingEnabled = com.aiassistant.AppSettingsService.getInstance().isThinkingEnabled()
        val requestBody = adapter.buildRequest(effectivePrompt, history, toolsJson, modelOverride = model, thinkingEnabled = thinkingEnabled)

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
                                if (inToolUse) {
                                    toolCalls.add(ToolCallResult(
                                        currentToolId, currentToolName,
                                        currentToolInput.toString().ifEmpty { "{}" }
                                    ))
                                    inToolUse = false
                                }
                            }
                            is ParsedEvent.MessageStart -> { hasResponse = true; AppLogger.info("SSE#$sseSeq $evName") }
                            is ParsedEvent.ThinkingStart -> { hasResponse = true }
                            is ParsedEvent.ThinkingDelta -> {
                                thinkingBuffer.append(event.thinking)
                                edt { onThinkingDelta?.invoke(thinkingBuffer.toString()) }
                            }
                            is ParsedEvent.SignatureDelta -> {}
                            is ParsedEvent.MessageStop -> {
                                AppLogger.info("SSE#$sseSeq $evName → done.notifyAll")
                                synchronized(done) { done.notifyAll() }
                            }
                            else -> { /* 未处理的事件类型，不记录日志 */ }
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

        // 先检查是否已完成（防止回调在 wait() 前已通知导致永久挂起）
        synchronized(done) {
            if (!hasResponse && errorDetail == null) {
                try { done.wait(120_000) } catch (_: InterruptedException) {}
            }
        }
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

    /** 使用 Gson 完整解析 JSON 参数，嵌套对象/数组序列化为 JSON 字符串 */
    private fun parseParams(json: String): Map<String, String> {
        return try {
            val gson = com.google.gson.Gson()
            val raw = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyMap()
            raw.mapNotNull { (k, v) ->
                if (k == null) return@mapNotNull null
                val value = when (v) {
                    is String -> v
                    null -> ""
                    else -> gson.toJson(v)  // 嵌套对象/数组 → JSON 字符串，工具可按需二次解析
                }
                k.toString() to value
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
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
        val claudeMdContent = loadClaudeMdFiles()
        return """
You are an AI coding assistant in idea. Use tools to work with the project.

## Project: ${project.name}
Path: ${project.basePath ?: "unknown"}

## Tools
$toolList

## Planning
对于需要多步骤完成的复杂任务（如实现功能、重构代码、架构变更），你应该先调用 **create_plan** 工具创建执行计划。
简单任务（读取文件、回答单个问题、一行修改）不要创建计划。
创建计划后，每个步骤开始前调用 **update_plan_step**（status="in_progress"），完成后调用（status="done" + result 摘要），失败时调用（status="failed" + result 原因）。
$skillsSection
$claudeMdContent
## Rules
- Use tools to get real data; never guess
- Read before edit; report results in Chinese
- For git: use git_status/git_diff/git_log
- For search: use search_code (not execute_command grep)
- For commands: use execute_command
        """.trimIndent()
    }

    /** 按照 Claude Code 层级自动加载 CLAUDE.md 文件 */
    private fun loadClaudeMdFiles(): String {
        val parts = mutableListOf<String>()
        val basePath = project.basePath ?: return ""
        val home = System.getProperty("user.home") ?: return ""

        // 1. 用户全局 ~/.claude/CLAUDE.md
        val userGlobal = java.io.File(home, ".claude/CLAUDE.md")
        if (userGlobal.exists()) {
            try { parts.add(userGlobal.readText()) } catch (_: Exception) {}
        }

        // 2. 项目根 CLAUDE.md
        val projectRoot = java.io.File(basePath, "CLAUDE.md")
        if (projectRoot.exists()) {
            try { parts.add(projectRoot.readText()) } catch (_: Exception) {}
        }

        // 3. .claude/CLAUDE.md
        val dotClaude = java.io.File(basePath, ".claude/CLAUDE.md")
        if (dotClaude.exists()) {
            try { parts.add(dotClaude.readText()) } catch (_: Exception) {}
        }

        // 4. CLAUDE.local.md (个人覆盖，gitignored)
        val localMd = java.io.File(basePath, "CLAUDE.local.md")
        if (localMd.exists()) {
            try { parts.add(localMd.readText()) } catch (_: Exception) {}
        }

        if (parts.isEmpty()) return ""
        return "\n## CLAUDE.md\n\n${parts.joinToString("\n\n---\n\n")}"
    }

    /** 将 create_plan 元工具拼接到 tools JSON 末尾（ToolRegistryV3 的 ToolParameter 无法表达嵌套 items 子结构） */
    private fun buildToolsJsonWithPlan(): String {
        val base = ctx.toolRegistry.buildToolsJson()  // "[...]"（已缓存，含 JsonUtils.escapeJson 保护）
        val inner = base.removeSurrounding("[", "]")
        val all = listOfNotNull(inner.takeIf { it.isNotBlank() }, CREATE_PLAN_TOOL_JSON, UPDATE_PLAN_STEP_TOOL_JSON).joinToString(",")
        return "[$all]"
    }

    // 用于解析 create_plan JSON 参数的数据类
    private data class PlanArgs(val title: String, val steps: List<StepArg>)
    private data class StepArg(val subject: String, val description: String = "")

    /** 从 create_plan 的 JSON 参数中解析 Plan */
    private fun parsePlanFromArgs(args: String): AgentContext.Plan {
        val gson = com.google.gson.Gson()
        val planArgs = gson.fromJson(args, PlanArgs::class.java)
        val steps = planArgs.steps.mapIndexed { i, s ->
            AgentContext.Step(index = i + 1, subject = s.subject, description = s.description)
        }
        return AgentContext.Plan(title = planArgs.title, steps = steps.toMutableList())
    }

    /** 动态生成当前计划状态提示，每次 API 调用前注入，确保 LLM 不会忘记未完成的计划 */
    private fun buildPlanPrompt(): String {
        val plan = ctx.currentPlan ?: return ""
        val steps = plan.stepsSnapshot()
        if (steps.isEmpty() || plan.isComplete()) return ""
        val pendingCount = steps.count { it.status == AgentContext.StepStatus.PENDING }
        val inProgressCount = steps.count { it.status == AgentContext.StepStatus.IN_PROGRESS }
        val stepsSummary = steps.joinToString("\n") { s ->
            val statusMark = when (s.status) {
                AgentContext.StepStatus.DONE -> "✅"
                AgentContext.StepStatus.IN_PROGRESS -> "🔄"
                AgentContext.StepStatus.PENDING -> "⏳"
                AgentContext.StepStatus.FAILED -> "❌"
            }
            "  ${s.index}. $statusMark ${s.subject}${if (s.description.isNotBlank()) " — ${s.description}" else ""}"
        }
        return """
## Active Plan: ${plan.title}
Progress: ${plan.progress()} (${steps.size} steps total, $inProgressCount in progress, $pendingCount pending)

$stepsSummary

CRITICAL: You have an active plan. You MUST continue executing it step by step.
- Call update_plan_step(index=<n>, status="in_progress") when starting a step
- Call update_plan_step(index=<n>, status="done", result="...") when completing a step
- After ask_user or any other tool result, proceed to the NEXT step — do NOT end the conversation
- Only stop when ALL steps are DONE. Do NOT output final text until plan is complete.
""".trimIndent()
    }

    private fun edt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
