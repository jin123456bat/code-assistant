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
        const val MAX_CONTINUATIONS = 5  // max_tokens 续写上限，防止死循环

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
    /** 是否为子 Agent（子 Agent 不注注册元工具，自动批准工具） */
    @Volatile private var isSubAgent = false
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
    /** 工具执行期间的中间输出回调（toolName → partialContent），用于子 Agent 实时输出 */
    var onToolStreaming: ((String, String) -> Unit)? = null

    fun initialize(
        mcpTools: List<AgentTool> = emptyList(),
        allowedTools: Set<String>? = null,
        deniedTools: Set<String> = emptySet(),
        asSubAgent: Boolean = false
    ) {
        isSubAgent = asSubAgent
        ctx.toolRegistry.registerBuiltIn(allowedTools, deniedTools)
        ctx.toolRegistry.registerMcp(mcpTools)
        // 子 Agent 不加载 skill 和自定义 agent（不需要这些系统）
        if (!isSubAgent) {
            val basePath = project.basePath
            val skillDefs = if (basePath != null) SkillEngine.loadProjectSkills(basePath) else emptyList()
            skillDefs.forEach { ctx.skillDefs[it.name] = it }
            // 加载自定义 Agent 定义（兼容 Claude Code .claude/agents/*.md）
            AgentTypes.loadCustom(basePath)
        }
        ctx.systemPrompt = buildSystemPrompt()
    }

    // Fork 上下文继承：子 Agent 继承父对话历史（复用 prompt cache 省 token）
    private var forkHistory: List<AnthropicMessage>? = null
    // Worktree 隔离：子 Agent 的工作目录（git worktree path）
    var workTreePath: String? = null

    fun run(userMessage: String, apiKey: String, images: List<ImageData>? = null, activatedSkill: String? = null,
            forkHistory: List<AnthropicMessage>? = null, callback: (String, String) -> Unit) {
        this.forkHistory = forkHistory
        cancelled = false
        onStateChange?.invoke(true)
        // 恢复或重建 system prompt：skill 激活时排除该 skill，普通消息时恢复完整 skill 列表
        ctx.activatedSkill = activatedSkill
        ctx.systemPrompt = buildSystemPrompt()
        // 跨轮对话历史：每轮追加到 ctx.conversationHistory，使 LLM 能感知完整上下文
        val history = ctx.conversationHistory

        val t = Thread {
            try {
                // 健康检查：恢复崩溃的 MCP 服务器（对齐 Claude Code，后台线程避免阻塞 EDT）
                com.aiassistant.mcp.McpManager.getInstance(project.basePath)?.healthCheck()

                // 自动 Compact：历史过长时先压缩再发送（对齐 Claude Code 自动 token 阈值触发）
                if (shouldAutoCompact(history)) {
                    AppLogger.info("自动 Compact 触发: historySize=${history.size}")
                    autoCompact(history, apiKey)
                }

                // 诊断信息
                val toolCount = ctx.toolRegistry.getAll().size
                val toolNames = ctx.toolRegistry.getAll().joinToString(", ") { it.name }
                edt { onMessage?.invoke(AgentMessage("system", "$toolCount 个工具已就绪: $toolNames")) }

                // Fork：注入父对话上下文（复用 prompt cache 省 token）
                forkHistory?.let { history.addAll(0, it) }
                history.add(AnthropicMessage("user", userMessage, images = images))

                // 检查并行子代理结果，注入到对话（主 Agent 下一轮 API 调用可感知）
                val subResults = SubAgentRegistry.drainCompleted()
                if (subResults.isNotEmpty()) {
                    AppLogger.info("AgentLoop: 注入 ${subResults.size} 个并行子代理结果")
                }
                for (entry in subResults) {
                    val resultText = when (entry.status) {
                        SubAgentRegistry.Status.DONE ->
                            "子代理 ${entry.id}（${entry.description}）已完成：\n${entry.result}"
                        SubAgentRegistry.Status.FAILED ->
                            "子代理 ${entry.id}（${entry.description}）失败：${entry.error}"
                        else -> null
                    }
                    if (resultText != null) {
                        history.add(AnthropicMessage("user", resultText))
                        edt { onMessage?.invoke(AgentMessage("sub_agent", resultText)) }
                    }
                }

                var loopCount = 0
                var consecutiveFailures = 0
                var continuationCount = 0

                while (loopCount < MAX_LOOPS && !cancelled) {
                    // 每轮检查并行子代理结果，注入到 history
                    val newSubResults = SubAgentRegistry.drainCompleted()
                    for (entry in newSubResults) {
                        val text = when (entry.status) {
                            SubAgentRegistry.Status.DONE ->
                                "子代理 ${entry.id}（${entry.description}）已完成：\n${entry.result}"
                            SubAgentRegistry.Status.FAILED ->
                                "子代理 ${entry.id}（${entry.description}）失败：${entry.error}"
                            else -> null
                        }
                        if (text != null) {
                            history.add(AnthropicMessage("user", text))
                            edt { onMessage?.invoke(AgentMessage("sub_agent", text)) }
                            AppLogger.info("AgentLoop: 注入子代理结果 ${entry.id} status=${entry.status}")
                        }
                    }

                    edt { onThinking?.invoke("思考中...") }

                    val result = callAnthropic(apiKey, history)
                    if (result == null) {
                        edt { onError?.invoke("API 调用失败") }
                        callback("", "")  // 通知调用方（如 TaskTool）执行失败
                        break
                    }

                    val (textContent, thinking, thinkingSignature, toolCalls, stopReason) = result
                    val truncated = stopReason == "max_tokens"
                    if (truncated) {
                        continuationCount++
                        AppLogger.info("AgentLoop: max_tokens 截断，续写 #$continuationCount（textLen=${textContent.length}, tools=${toolCalls.size}）")
                        if (continuationCount >= MAX_CONTINUATIONS) {
                            AppLogger.warn("AgentLoop: 续写次数已达上限 $MAX_CONTINUATIONS，返回已有内容")
                        }
                    }
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
                                val skillParams = parseParams(tc.arguments)
                                val skillName = skillParams["skill"]?.takeIf { it.isNotBlank() }
                                val skillArgs = skillParams["args"]
                                if (skillName == null) {
                                    val errMsg = "Skill 调用失败：缺少 skill 参数。可用 skill: ${ctx.skillDefs.keys.joinToString(", ")}"
                                    if (!firstToolCallTextAdded) {
                                        if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                            history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                            thinkingBlockAdded = true
                                        }
                                        history.add(AnthropicMessage("assistant", textContent))
                                        firstToolCallTextAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                    history.add(AnthropicMessage("user", errMsg, toolCallId = tc.id))
                                    edt { onToolResult?.invoke(tc.name, errMsg) }
                                    continue
                                }
                                val def = ctx.skillDefs[skillName]
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
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", skillResult, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, skillResult) }
                                continue
                            }

                            // create_plan 元工具：LLM 自主决定是否创建执行计划
                            if (tc.name == "create_plan") {
                                val newPlan = parsePlanFromArgs(tc.arguments)
                                // 如果 steps 为空（LLM 未提供步骤），返回错误提示让 LLM 修正重试
                                if (newPlan.stepsSnapshot().isEmpty()) {
                                    val errorResult = "计划创建失败：缺少有效的 steps 参数。请提供至少一个步骤（含 subject 和可选的 description）后重试。"
                                    if (!firstToolCallTextAdded) {
                                        if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                            AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                            history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                            thinkingBlockAdded = true
                                        }
                                        history.add(AnthropicMessage("assistant", textContent))
                                        firstToolCallTextAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                    history.add(AnthropicMessage("user", errorResult, toolCallId = tc.id))
                                    edt { onToolResult?.invoke(tc.name, errorResult) }
                                    continue
                                }
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
                                    // thinking 记录在 history 中供 UI 展示，SDK 层已跳过不回传 Anthropic 协议
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                        history.add(AnthropicMessage(
                                            "assistant", "",
                                            thinking = thinking,
                                            thinkingSignature = thinkingSignature
                                        ))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage(
                                    "assistant", "", toolUseId = tc.id,
                                    toolName = tc.name, toolInput = tc.arguments,
                                    thinking = thinking, thinkingSignature = thinkingSignature
                                ))
                                history.add(AnthropicMessage(
                                    "user", planResult, toolCallId = tc.id
                                ))
                                edt { onToolResult?.invoke(tc.name, planResult) }
                                continue
                            }

                            // update_plan_step 元工具：LLM 更新计划步骤状态
                            if (tc.name == "update_plan_step") {
                                val stepParams = parseParams(tc.arguments)
                                val stepIndex = stepParams["index"]?.toIntOrNull()
                                val status = stepParams["status"]
                                val stepResult = stepParams["result"]
                                var updated = false
                                val msg = when {
                                    stepIndex == null -> "更新失败：缺少或无效的 index 参数，请提供步骤编号（整数）"
                                    status == null -> "更新失败：缺少 status 参数，请提供 in_progress / done / failed 之一"
                                    else -> {
                                        val stepStatus = when (status) {
                                            "in_progress" -> AgentContext.StepStatus.IN_PROGRESS
                                            "done" -> AgentContext.StepStatus.DONE
                                            "failed" -> AgentContext.StepStatus.FAILED
                                            else -> null
                                        }
                                        if (stepStatus == null) {
                                            "更新失败：无效的 status 值 '$status'，仅支持 in_progress / done / failed"
                                        } else {
                                            updated = ctx.currentPlan?.updateStep(stepIndex, stepStatus, stepResult) ?: false
                                            if (updated) {
                                                ctx.currentPlan?.let { edt { onPlanUpdate?.invoke(it) } }
                                                "步骤 $stepIndex 状态更新为 $status"
                                            } else {
                                                "更新失败：步骤 $stepIndex 不存在，当前计划共 ${ctx.currentPlan?.stepsSnapshot()?.size ?: 0} 步"
                                            }
                                        }
                                    }
                                }
                                if (!firstToolCallTextAdded) {
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        history.add(AnthropicMessage(
                                            "assistant", "",
                                            thinking = thinking,
                                            thinkingSignature = thinkingSignature
                                        ))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            val params = parseParams(tc.arguments).toMutableMap()
                            // Worktree 隔离：子 Agent 的工具操作指向独立 worktree
                            workTreePath?.let { params["_worktree"] = it }
                            // Fork：注入父对话历史（JSON 序列化）供 TaskTool 使用
                            if (tc.name == "task") {
                                params["_forkHistory"] = com.google.gson.Gson().toJson(history.toList())
                            }
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
                                val r = ctx.toolRegistry.executeTool(tc.name, params, project) { partial ->
                                    onToolStreaming?.invoke(tc.name, partial)
                                }
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
                                if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                    AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                    history.add(AnthropicMessage(
                                        "assistant", "",
                                        thinking = thinking,
                                        thinkingSignature = thinkingSignature
                                    ))
                                    thinkingBlockAdded = true
                                }
                                history.add(AnthropicMessage("assistant", textContent))
                                firstToolCallTextAdded = true
                            }
                            history.add(AnthropicMessage(
                                "assistant", "", toolUseId = tc.id,
                                toolName = tc.name, toolInput = tc.arguments,
                                thinking = thinking, thinkingSignature = thinkingSignature
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

                        if (consecutiveFailures >= MAX_FAILURES) {
                            edt { onError?.invoke("连续失败超过 $MAX_FAILURES 次，已中止") }
                            callback("", "")  // 通知调用方执行失败
                            break
                        }
                        if (truncated && continuationCount >= MAX_CONTINUATIONS) {
                            AppLogger.warn("AgentLoop: 续写次数已达上限 $MAX_CONTINUATIONS（工具调用后），返回已有内容")
                            callback(textContent, thinking)
                            break
                        }
                    } else {
                        // 无工具调用
                        if (truncated) {
                            // max_tokens 截断：将已生成内容加入 history
                            if (thinking.isNotBlank()) {
                                history.add(AnthropicMessage("assistant", textContent,
                                    thinking = thinking, thinkingSignature = thinkingSignature))
                            } else {
                                history.add(AnthropicMessage("assistant", textContent))
                            }
                            if (continuationCount >= MAX_CONTINUATIONS) {
                                // 续写次数达上限，返回已有内容
                                AppLogger.warn("AgentLoop: 续写次数已达上限 $MAX_CONTINUATIONS，返回已有内容")
                                callback(textContent, thinking)
                                break
                            }
                            // 继续循环让模型续写
                            AppLogger.info("AgentLoop: max_tokens 截断续写，textLen=${textContent.length}")
                            loopCount++
                            continue
                        }
                        // 正常结束：thinking 仅用于 UI 展示，SDK 层已跳过不回传
                        AppLogger.info("AgentLoop 最终回复: $textContent thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                        if (thinking.isNotBlank()) {
                            history.add(AnthropicMessage("assistant", textContent,
                                thinking = thinking, thinkingSignature = thinkingSignature))
                        } else {
                            history.add(AnthropicMessage("assistant", textContent))
                        }
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
        val toolCalls: List<ToolCallResult>,
        val stopReason: String = "end_turn"
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
        var capturedStopReason = "end_turn"

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
            maxTokens = com.aiassistant.AnthropicAdapter.MAX_TOKENS,
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
                override fun onStreamComplete(textContent: String, thinking: String, thinkingSig: String, sdkToolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>, inputTokens: Int, stopReason: String) {
                    ctx.lastInputTokens = inputTokens
                    textBuffer.clear(); textBuffer.append(textContent)
                    thinkingBuffer.clear(); thinkingBuffer.append(thinking)
                    thinkingSignature = thinkingSig
                    capturedStopReason = stopReason
                    sdkToolCalls.forEach { tc ->
                        toolCalls.add(ToolCallResult(tc.id, tc.name, tc.arguments))
                    }
                    AppLogger.info("SDK stream complete: textLen=${textContent.length} thinkingLen=${thinking.length} thinkingSigLen=${thinkingSig.length} tools=${toolCalls.size} stopReason=$stopReason")
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
            return AnthropicResponse(thinking, "", thinkingSignature, resultToolCalls, capturedStopReason)
        }

        return AnthropicResponse(finalText, thinking, thinkingSignature, resultToolCalls, capturedStopReason)
    }

    /**
     * 压缩对话历史：使用 LLM 生成摘要，保留关键信息，释放 token 预算。
     * 对齐 Claude Code /compact：保留原始任务、关键决策、文件变更、计划进度。
     * @param messages 待压缩的消息列表
     * @param apiKey API Key
     * @return 摘要文本，失败返回 null
     */
    fun compact(messages: List<AnthropicMessage>, apiKey: String): String? {
        if (messages.isEmpty()) return null
        // 复用 SDK 客户端
        if (sdkClient == null || lastApiKey != apiKey) {
            sdkClient = com.aiassistant.AnthropicSdkClient(apiKey)
            lastApiKey = apiKey
        }
        val sdkClient = sdkClient!!

        // 构建压缩提示词
        val compactPrompt = buildString {
            append("你是一个对话摘要助手。请将以下对话历史压缩为一段简洁的中文摘要，")
            append("保留以下关键信息：\n")
            append("1. 用户的原始任务/目标\n")
            append("2. 已完成的关键操作和决策\n")
            append("3. 修改过的文件及原因\n")
            append("4. 遇到的错误及解决方案\n")
            append("5. 当前执行计划进度（如有）\n")
            append("6. 重要的技术细节和约定\n")
            append("\n请用 200-500 字输出摘要，不要使用标题或列表标记。\n\n")
            append("对话历史：\n")
            for (msg in messages) {
                // 跳过 thinking-only 消息（不包含实质内容，UI 展示用）
                if (msg.thinking.isNotBlank() && msg.content.isBlank() && msg.toolUseId == null) continue
                val prefix = when {
                    msg.toolCallId != null -> "工具结果"
                    msg.toolUseId != null -> "工具调用(${msg.toolName ?: ""})"
                    msg.role == "user" -> "用户"
                    else -> "AI"
                }
                val content = msg.content.take(2000)  // 每条最多 2000 字符
                append("[$prefix] $content\n")
            }
        }

        // 构建摘要消息列表：system prompt + 压缩请求
        val summaryHistory = listOf(
            AnthropicMessage("user", compactPrompt)
        )

        // 一次性调用，不传工具、不启用 thinking
        val result = try {
            val done = Object()
            var summaryText = ""
            var hasError = false
            sdkClient.createStreaming(
                model = model,
                systemPrompt = "你是一个简洁的对话摘要助手，用中文输出。",
                messages = summaryHistory,
                tools = emptyList(),
                thinkingEnabled = false,
                callback = object : com.aiassistant.AnthropicSdkClient.Callback {
                    override fun onTextDelta(fullText: String) { summaryText = fullText }
                    override fun onThinkingDelta(fullThinking: String) {}
                    override fun onToolUseStart(id: String, name: String) {}
                    override fun onToolInputDelta(partial: String) {}
                    override fun onStreamComplete(textContent: String, thinking: String, thinkingSignature: String, toolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>, inputTokens: Int, stopReason: String) {
                        summaryText = textContent
                        synchronized(done) { done.notifyAll() }
                    }
                    override fun onError(error: Throwable) {
                        hasError = true
                        synchronized(done) { done.notifyAll() }
                    }
                }
            )
            synchronized(done) {
                if (summaryText.isEmpty() && !hasError) {
                    try { done.wait(60_000) } catch (_: InterruptedException) {}
                }
            }
            if (hasError || summaryText.isBlank()) null else summaryText.trim()
        } catch (e: Exception) {
            AppLogger.warn("对话压缩失败: ${e.message}")
            null
        }

        return result
    }

    /**
     * 判断是否超过上下文窗口的比例阈值。
     * 使用最近一次 API 返回的 inputTokens（代表当前对话历史占用的 token 数），
     * 因为上下文窗口限制只针对 input（输出 token 不影响下次请求）。
     * 若 SDK 未返回 inputTokens（=0），降级为字符估算。
     */
    private fun shouldAutoCompact(history: List<AnthropicMessage>): Boolean {
        if (history.size < 30) return false
        val contextWindow = 1_000_000L
        val ratio = com.aiassistant.AppSettingsService.getInstance().getCompactRatio()
        val threshold = (contextWindow * ratio).toLong()
        // 优先使用 API 返回的精确 input token 数
        val tokens = ctx.lastInputTokens
        if (tokens > 0) return tokens > threshold
        // 降级：字符数估算
        val allText = history.joinToString("") { it.content + it.thinking }
        val cjkCount = allText.count { it in '一'..'鿿' }
        val otherCount = allText.length - cjkCount
        val estimatedTokens = (cjkCount * 1.5 + otherCount * 0.25).toLong()
        return estimatedTokens > threshold
    }

    /**
     * 自动 Compact：静默压缩旧消息，摘要注入 system prompt。
     * 对齐 Claude Code 自动 token 阈值触发机制。
     */
    private fun autoCompact(history: MutableList<AnthropicMessage>, apiKey: String) {
        if (history.size <= 30) return
        // 保留最近约 30 条历史记录（对应约 10 轮对话）
        val historyKeep = history.takeLast(30).toList()
        val historyToSummarize = history.dropLast(30).toList()
        if (historyToSummarize.isEmpty()) return

        val summary = compact(historyToSummarize, apiKey) ?: return
        // 摘要注入 system prompt（对齐 Claude Code，不破坏消息交替结构）
        ctx.systemPrompt = buildString {
            append(ctx.systemPrompt)
            append("\n\n## 对话摘要（自动生成）\n$summary")
        }
        // 重建历史：仅保留最近的消息
        synchronized(ctx.historyLock) {
            history.clear()
            history.addAll(historyKeep)
        }
        AppLogger.info("自动 Compact 完成: ${historyToSummarize.size} 条 → 1 段摘要, 保留 ${historyKeep.size} 条")
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
        // 子 Agent 不需要元工具（Skill/create_plan/update_plan_step）
        if (isSubAgent) return builtIn
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
    private data class PlanArgs(val title: String?, val steps: List<StepArg>?)
    private data class StepArg(val subject: String?, val description: String? = null)

    /** 从 create_plan 的 JSON 参数中解析 Plan。
     *  容错：LLM 可能返回 null/missing fields，使用 try-catch + 空值兜底防止 NPE 中断 AgentLoop。 */
    private fun parsePlanFromArgs(args: String): AgentContext.Plan {
        return try {
            val gson = com.google.gson.Gson()
            val planArgs = gson.fromJson(args, PlanArgs::class.java)
            val steps = (planArgs.steps ?: emptyList()).mapIndexed { i, s ->
                AgentContext.Step(
                    index = i + 1,
                    subject = s.subject ?: "步骤 ${i + 1}",
                    description = s.description ?: ""
                )
            }
            AgentContext.Plan(
                title = planArgs.title?.takeIf { it.isNotBlank() } ?: "执行计划",
                steps = steps.toMutableList()
            )
        } catch (e: Exception) {
            AppLogger.warn("解析 create_plan 参数失败: ${e.message}, args=$args")
            AgentContext.Plan(title = "执行计划", steps = mutableListOf())
        }
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
