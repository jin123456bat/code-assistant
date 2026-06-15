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
    private val project: Project
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

        /** 统一 Skill 元工具 input_schema（对齐 Claude Code：单一 Skill 工具，参数 skill + args） */
        private const val SKILL_TOOL_SCHEMA = """{"type":"object","properties":{"skill":{"type":"string","description":"要激活的 skill 名称（如 frontend-design、code-review）"},"args":{"type":"string","description":"传递给 skill 的用户输入内容"}},"required":["skill"]}"""

        private const val SKILL_TOOL_JSON = """{"name":"Skill","description":"激活一个 skill，获取特定领域的专业指引。可选参数 args 传递用户输入。","input_schema":$SKILL_TOOL_SCHEMA}"""
    }

    val ctx = AgentContext(project)
    @Volatile private var cancelled = false
    @Volatile private var model: String = AppSettingsService.getInstance().getModel()
    /** 当前 agent 后台线程引用，供 stop() 中断阻塞等待（网络挂起时无需等 2 分钟超时） */
    @Volatile private var agentThread: Thread? = null
    /** 复用的 SDK 客户端（含 OkHttp 连接池），避免每轮 API 调用创建新的连接池导致线程/连接泄漏 */
    @Volatile private var sdkClient: com.aiassistant.AnthropicSdkClient? = null
    private var lastApiKey: String? = null

    /** 刷新模型配置，用于 Settings 变更后同步 */
    fun refreshModel() {
        model = AppSettingsService.getInstance().getModel()
    }

    /** 切换到指定模型（skill preferredModel 路由） */
    fun switchModel(newModel: String) {
        if (newModel != model) {
            AppLogger.info("AgentLoop模型切换: $model → $newModel")
            model = newModel
            edt { onModelRouted?.invoke(newModel) }
        }
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
        // 加载 skill 定义到 ctx.skillDefs（不再注册为独立工具，对齐 Claude Code 统一 Skill 元工具）
        val basePath = project.basePath
        val skillDefs = if (basePath != null) SkillEngine.loadProjectSkills(basePath) else emptyList()
        skillDefs.forEach { ctx.skillDefs[it.name] = it }
        ctx.systemPrompt = buildSystemPrompt()
    }

    fun run(userMessage: String, apiKey: String, images: List<ImageData>? = null, activatedSkill: String? = null, callback: (String, String) -> Unit) {
        cancelled = false
        onStateChange?.invoke(true)
        // 恢复或重建 system prompt：skill 激活时排除该 skill，普通消息时恢复完整 skill 列表
        ctx.activatedSkill = activatedSkill
        ctx.systemPrompt = buildSystemPrompt()
        val history = mutableListOf<AnthropicMessage>()

        val t = Thread {
            try {
                // 健康检查：恢复崩溃的 MCP 服务器（对齐 Claude Code，后台线程避免阻塞 EDT）
                com.aiassistant.mcp.McpManager.getInstance(project.basePath)?.healthCheck()
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

                    val (textContent, thinking, thinkingSignature, toolCalls) = result
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
                        // thinking content block 也只需在第一个 toolCall 时添加一次
                        var thinkingBlockAdded = false

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

                            // Skill 元工具：统一的 skill 激活入口（对齐 Claude Code）
                            if (tc.name == "Skill") {
                                val skillName = Regex(""""skill"\s*:\s*"([^"]*)"""").find(tc.arguments)?.groupValues?.get(1)
                                val skillArgs = Regex(""""args"\s*:\s*"([^"]*)"""").find(tc.arguments)?.groupValues?.get(1)
                                val def = if (skillName != null) ctx.skillDefs[skillName] else null
                                val skillResult = if (def != null) {
                                    // 注入 skill prompt 到 system prompt（对齐 Claude Code）
                                    ctx.activatedSkillPrompt = def.prompt
                                    ctx.activatedSkill = skillName
                                    ctx.systemPrompt = buildSystemPrompt()
                                    // Skill 模型路由
                                    if (def.preferredModel != null && def.preferredModel != model) {
                                        AppLogger.info("Skill模型路由: '$model' → '${def.preferredModel}' (skill: $skillName)")
                                        model = def.preferredModel!!
                                        edt { onModelRouted?.invoke(def.preferredModel) }
                                    }
                                    "Skill '$skillName' 已激活。${if (!skillArgs.isNullOrBlank()) "用户输入: $skillArgs" else ""}"
                                } else {
                                    "未知 Skill: $skillName。可用 skill: ${ctx.skillDefs.keys.joinToString(", ")}"
                                }
                                if (!firstToolCallTextAdded) {
                                    if (!thinkingBlockAdded && thinking.isNotBlank() && thinkingSignature.isNotBlank()) {
                                        history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent, toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("user", skillResult, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, skillResult) }
                                continue
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
                                    // thinking content block 必须在 assistant 消息中随后续请求传回 API
                                    if (!thinkingBlockAdded && thinking.isNotBlank() && thinkingSignature.isNotBlank()) {
                                        AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                        history.add(AnthropicMessage(
                                            "assistant", "",
                                            thinking = thinking,
                                            thinkingSignature = thinkingSignature
                                        ))
                                        thinkingBlockAdded = true
                                    } else if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        AppLogger.warn("AgentLoop: thinking存在但signature为空! thinkingLen=${thinking.length} sigLen=${thinkingSignature.length} — 不会回传")
                                    }
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
                                    if (!thinkingBlockAdded && thinking.isNotBlank() && thinkingSignature.isNotBlank()) {
                                        history.add(AnthropicMessage(
                                            "assistant", "",
                                            thinking = thinking,
                                            thinkingSignature = thinkingSignature
                                        ))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent, toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            val params = parseParams(tc.arguments)
                            // read_file 特殊处理：仅在项目目录内自动放行，跨目录需用户确认
                            val readFileOutside = tc.name == "read_file" && params["path"] != null &&
                                !com.aiassistant.shared.PathUtils.isInsideProject(params["path"]!!, project.basePath)
                            val approved = if (!readFileOutside && (tc.name in SAFE_TOOLS || tc.name in AppSettingsService.getInstance().getToolWhitelist())) {
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
                                // MCP 工具：注入原始 JSON 参数，保留 number/boolean/array/object 类型
                                val mcpAdapter = ctx.toolRegistry.find(tc.name) as? com.aiassistant.mcp.McpToolAdapter
                                if (mcpAdapter != null) { mcpAdapter.rawArgsJson = tc.arguments }
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
                                // thinking content block 必须在 assistant 消息中随后续请求传回 API
                                if (!thinkingBlockAdded && thinking.isNotBlank() && thinkingSignature.isNotBlank()) {
                                    AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                    history.add(AnthropicMessage(
                                        "assistant", "",
                                        thinking = thinking,
                                        thinkingSignature = thinkingSignature
                                    ))
                                    thinkingBlockAdded = true
                                } else if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                    AppLogger.warn("AgentLoop: thinking存在但signature为空! thinkingLen=${thinking.length} sigLen=${thinkingSignature.length} — 不会回传")
                                }
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
                        AppLogger.info("AgentLoop 最终回复: $textContent")
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
        }
        agentThread = t
        t.start()
    }

    fun stop() {
        cancelled = true
        // 中断阻塞等待（done.wait / latch.await）：网络挂起时无需等 2 分钟超时
        agentThread?.interrupt()
        // 通知所有 MCP 服务器取消正在执行的操作（对齐 Claude Code）
        com.aiassistant.mcp.McpManager.getInstance(project.basePath)?.cancelAll()
        // 解除可能正卡在用户确认上的背景线程，避免挂起到超时
        pendingConfirmLatch?.countDown()
    }

    // ---- Anthropic API ----

    data class ToolCallResult(val id: String, val name: String, val arguments: String)

    data class AnthropicResponse(
        val textContent: String,
        val thinking: String,
        val thinkingSignature: String,
        val toolCalls: List<ToolCallResult>
    )

    private fun callAnthropic(
        apiKey: String, history: List<AnthropicMessage>
    ): AnthropicResponse? {
        val planPrompt = buildPlanPrompt()
        val skillPrompt = ctx.activatedSkillPrompt
        val effectivePrompt = buildString {
            append(ctx.systemPrompt)
            if (skillPrompt != null) { append("\n\n---\n\n## 已激活 Skill\n\n"); append(skillPrompt) }
            if (planPrompt.isNotEmpty()) { append("\n\n"); append(planPrompt) }
        }
        val thinkingEnabled = com.aiassistant.AppSettingsService.getInstance().isThinkingEnabled()

        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        val toolCalls = mutableListOf<ToolCallResult>()
        val done = Object()
        var hasResponse = false
        var errorDetail: String? = null
        var thinkingSignature = ""

        // 复用 SDK 客户端，仅在 API Key 变更时重建，避免每轮创建新连接池
        if (sdkClient == null || lastApiKey != apiKey) {
            sdkClient = com.aiassistant.AnthropicSdkClient(apiKey)
            lastApiKey = apiKey
        }
        val sdkClient = sdkClient!!
        val tools = buildSdkToolDefs()

        sdkClient.createStreaming(
            model = model ?: "deepseek-v4-pro",
            systemPrompt = effectivePrompt,
            messages = history,
            tools = tools,
            thinkingEnabled = thinkingEnabled,
            callback = object : com.aiassistant.AnthropicSdkClient.Callback {
                override fun onTextDelta(fullText: String) {
                    hasResponse = true
                    textBuffer.clear(); textBuffer.append(fullText)
                    edt { onStreaming?.invoke(fullText) }
                }
                override fun onThinkingDelta(fullThinking: String) {
                    thinkingBuffer.clear(); thinkingBuffer.append(fullThinking)
                    edt { onThinkingDelta?.invoke(fullThinking) }
                }
                override fun onToolUseStart(id: String, name: String) {
                    hasResponse = true
                    AppLogger.info("SDK tool_use: id=$id name=$name")
                }
                override fun onToolInputDelta(partial: String) {}
                override fun onStreamComplete(textContent: String, thinking: String, thinkingSig: String, sdkToolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>) {
                    textBuffer.clear(); textBuffer.append(textContent)
                    thinkingBuffer.clear(); thinkingBuffer.append(thinking)
                    thinkingSignature = thinkingSig
                    sdkToolCalls.forEach { tc ->
                        toolCalls.add(ToolCallResult(tc.id, tc.name, tc.arguments))
                    }
                    AppLogger.info("SDK stream complete: textLen=${textContent.length} thinkingLen=${thinking.length} thinkingSigLen=${thinkingSig.length} tools=${toolCalls.size}")
                    synchronized(done) { done.notifyAll() }
                }
                override fun onError(error: Throwable) {
                    errorDetail = error.message?.take(500) ?: "SDK error: ${error.javaClass.simpleName}"
                    AppLogger.error("SDK streaming error: ${error.message}\n${error.stackTraceToString().take(1000)}")
                    synchronized(done) { done.notifyAll() }
                }
            }
        )

        // 等待流结束
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
        val resultToolCalls = toolCalls.toList()

        AppLogger.info("callAnthropic 返回: finalText.length=${finalText.length} thinking.length=${thinking.length} thinkingSigLen=${thinkingSignature.length} toolCalls.size=${resultToolCalls.size}")

        if (resultToolCalls.isEmpty() && finalText.isEmpty() && thinking.isNotEmpty()) {
            AppLogger.info("callAnthropic: thinking 降级为 text（DeepSeek V4 behavior）")
            return AnthropicResponse(thinking, "", thinkingSignature, resultToolCalls)
        }

        return AnthropicResponse(finalText, thinking, thinkingSignature, resultToolCalls)
    }

    /** 将 ToolRegistryV3 中的工具 + 元工具转换为 SDK 格式 */
    private fun buildSdkToolDefs(): List<com.aiassistant.AnthropicToolDef> {
        val builtIn = ctx.toolRegistry.getAll()
            .map { tool ->
                com.aiassistant.AnthropicToolDef(
                    name = tool.name,
                    description = tool.description,
                    properties = tool.parameters.associate { p ->
                        p.name to com.aiassistant.PropertyDef(p.type, p.description)
                    }.ifEmpty { null },
                    required = tool.parameters.filter { it.required }.map { it.name }.ifEmpty { null }
                )
            }
        // 元工具（由 AgentLoop 硬编码处理，不在 ToolRegistryV3 中）
        val metaTools = listOf(
            com.aiassistant.AnthropicToolDef("Skill", "激活一个 skill，获取特定领域的专业指引"),
            com.aiassistant.AnthropicToolDef("create_plan", "创建执行计划"),
            com.aiassistant.AnthropicToolDef("update_plan_step", "更新计划步骤状态")
        )
        return builtIn + metaTools
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

    private fun buildSystemPrompt(): String {
        val tools = ctx.toolRegistry.getAll()
        val toolList = tools.joinToString("\n") { t ->
            val params = t.parameters.joinToString(", ") { "${it.name}:${it.type}" }
            "- **${t.name}**($params): ${t.description}"
        }
        // Skills 段落：展示可用 skill 列表（排除已激活的），渐进披露
        val activeSkill = ctx.activatedSkill
        val availableSkills = ctx.skillDefs.values.filter { it.name != activeSkill }
        val skillsSection = if (availableSkills.isNotEmpty()) {
            "\n## Skills\n" + availableSkills.joinToString("\n") { s ->
                "- **${s.name}**: ${s.description}"
            } + "\n\n可通过 /skill名称 激活 Skill，或调用 **Skill** 工具动态激活。"
        } else ""
        // MCP Prompts 段落（对齐 Claude Code：MCP 服务器提供的 prompt 模板）
        val promptsSection = if (ctx.mcpPrompts.isNotEmpty()) {
            "\n## MCP Prompts\n" + ctx.mcpPrompts.joinToString("\n") { p ->
                val argsStr = if (p.arguments.isNotEmpty()) "（参数: ${p.arguments.joinToString { "${it.name}:${it.type}" }}）" else ""
                "- **${p.name}**$argsStr: ${p.description}"
            } + "\n\n需要时可通过 **mcp_get_prompt** 工具获取完整渲染内容（传入 name 和可选的 arguments JSON）。"
        } else ""
        // MCP Resources 段落（对齐 Claude Code：MCP 服务器提供的资源）
        val resourcesSection = if (ctx.mcpResources.isNotEmpty()) {
            "\n## MCP Resources\n" + ctx.mcpResources.joinToString("\n") { r ->
                "- `${r.uri}` — ${r.name}${if (r.description.isNotBlank()) ": ${r.description}" else ""}"
            } + "\n\n可通过 **read_file** 工具读取上述 MCP 资源（传入 resource:// URI 作为 path 参数）。"
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
$promptsSection
$resourcesSection
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
