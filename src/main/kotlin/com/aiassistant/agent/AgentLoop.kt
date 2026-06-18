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
        const val MAX_LOOPS = Int.MAX_VALUE  // 主 Agent 不限制轮次（对齐 Claude Code）
        const val MAX_SUB_LOOPS = 20         // 子 Agent 默认最大轮次（对齐 Claude Code maxTurns）
        const val MAX_FAILURES = 3
        const val MAX_CONTINUATIONS = 5  // max_tokens 续写上限，防止死循环

        /** 安全工具白名单 — 无需用户确认直接执行 + Plan Mode 只读工具集 */
        val SAFE_TOOLS = setOf(
            "search_code", "read_file", "list_directory",
            "git_diff", "git_log", "git_status", "web_search",
            "web_fetch", "task", "ask_user", "code_intelligence"
        )

        // ---- Meta-tool JSON schemas（对齐 Claude Code） ----

        /** 统一 Skill 元工具 */
        private const val SKILL_TOOL_SCHEMA = """{"type":"object","properties":{"skill":{"type":"string","description":"要激活的 skill 名称"},"args":{"type":"string","description":"传递给 skill 的用户输入内容"}},"required":["skill"]}"""
        private const val SKILL_TOOL_JSON = """{"name":"Skill","description":"激活一个 skill，获取特定领域的专业指引。可选参数 args 传递用户输入。","input_schema":$SKILL_TOOL_SCHEMA}"""

        /** EnterPlanMode — 进入规划模式（只读，禁止写操作） */
        private const val ENTER_PLAN_MODE_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""
        private const val ENTER_PLAN_MODE_JSON = """{"name":"EnterPlanMode","description":"进入规划模式，只允许只读工具。在此模式下探索代码库、设计方案，完成规划后调用 ExitPlanMode。","input_schema":$ENTER_PLAN_MODE_SCHEMA}"""

        /** ExitPlanMode — 提交规划方案，触发用户审批 */
        private const val EXIT_PLAN_MODE_SCHEMA = """{"type":"object","properties":{"plan":{"type":"string","description":"规划方案（markdown 格式），包含技术方案、实施步骤、风险点等"}},"required":["plan"],"additionalProperties":false}"""
        private const val EXIT_PLAN_MODE_JSON = """{"name":"ExitPlanMode","description":"提交规划方案并请求用户审批。用户批准后方可执行写操作。","input_schema":$EXIT_PLAN_MODE_SCHEMA}"""

        /** TaskCreate — 创建单个任务 */
        private const val TASK_CREATE_SCHEMA = """{"type":"object","properties":{"subject":{"type":"string","description":"任务简短标题（显示在任务列表中）"},"description":{"type":"string","description":"任务详细描述（可选）"}},"required":["subject"],"additionalProperties":false}"""
        private const val TASK_CREATE_JSON = """{"name":"TaskCreate","description":"创建一个执行任务。用于将规划方案拆分为可追踪的任务项。","input_schema":$TASK_CREATE_SCHEMA}"""

        /** TaskUpdate — 更新任务状态 */
        private const val TASK_UPDATE_SCHEMA = """{"type":"object","properties":{"id":{"type":"integer","description":"任务 ID（TaskCreate 返回的）"},"status":{"type":"string","enum":["in_progress","completed"],"description":"新状态：in_progress=开始执行, completed=已完成"},"result":{"type":"string","description":"完成时的结果摘要（status=completed 时使用）"}},"required":["id","status"],"additionalProperties":false}"""
        private const val TASK_UPDATE_JSON = """{"name":"TaskUpdate","description":"更新任务状态。开始任务时标记 in_progress，完成后标记 completed。","input_schema":$TASK_UPDATE_SCHEMA}"""

        /** TaskList — 列出所有任务 */
        private const val TASK_LIST_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""
        private const val TASK_LIST_JSON = """{"name":"TaskList","description":"列出当前所有任务及其状态。","input_schema":$TASK_LIST_SCHEMA}"""

        /** TaskGet — 查询单个任务详情 */
        private const val TASK_GET_SCHEMA = """{"type":"object","properties":{"id":{"type":"integer","description":"任务 ID"}},"required":["id"],"additionalProperties":false}"""
        private const val TASK_GET_JSON = """{"name":"TaskGet","description":"查询指定任务的详细信息。","input_schema":$TASK_GET_SCHEMA}"""
    }

    val ctx = AgentContext(project)
    @Volatile private var cancelled = false
    @Volatile private var model: String = AppSettingsService.getInstance().getModel()
    private var maxLoops: Int = MAX_LOOPS
    @Volatile private var isSubAgent = false
    private var roundGroupId: Int = 1
    @Volatile private var agentThread: Thread? = null
    @Volatile private var sdkClient: com.aiassistant.AnthropicSdkClient? = null
    private var lastApiKey: String? = null

    fun refreshModel() { model = AppSettingsService.getInstance().getModel() }

    fun switchModel(newModel: String) {
        if (newModel != model) {
            AppLogger.info("AgentLoop模型切换: $model → $newModel")
            model = newModel
            edt { onModelRouted?.invoke(newModel) }
        }
    }

    @Volatile private var pendingConfirmLatch: CountDownLatch? = null

    var onMessage: ((AgentMessage) -> Unit)? = null
    var onStreaming: ((String) -> Unit)? = null
    var onThinking: ((String?) -> Unit)? = null
    var onToolExecute: ((String, String) -> Unit)? = null
    var onToolResult: ((String, String) -> Unit)? = null
    /** 任务列表变更回调（TaskCreate/TaskUpdate 触发） */
    var onTaskUpdate: (() -> Unit)? = null
    /** Plan Mode 审批回调（ExitPlanMode 触发，需用户审批） */
    var onConfirmPlan: ((String, CountDownLatch, AtomicBoolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChange: ((Boolean) -> Unit)? = null
    var onModelRouted: ((String) -> Unit)? = null
    var onThinkingDelta: ((String) -> Unit)? = null
    var onConfirmTool: ((String, String, CountDownLatch, AtomicBoolean) -> Unit)? = null
    var onToolStreaming: ((String, String) -> Unit)? = null
    var onCompact: ((String) -> Unit)? = null

    fun initialize(
        mcpTools: List<AgentTool> = emptyList(),
        allowedTools: Set<String>? = null,
        deniedTools: Set<String> = emptySet(),
        asSubAgent: Boolean = false,
        overrideMaxLoops: Int = MAX_LOOPS
    ) {
        isSubAgent = asSubAgent
        maxLoops = overrideMaxLoops
        ctx.toolRegistry.registerBuiltIn(allowedTools, deniedTools)
        ctx.toolRegistry.registerMcp(mcpTools)
        if (!isSubAgent) {
            val basePath = project.basePath
            val skillDefs = if (basePath != null) SkillEngine.loadProjectSkills(basePath) else emptyList()
            skillDefs.forEach { ctx.skillDefs[it.name] = it }
            AgentTypes.loadCustom(basePath)
            val loadedRules = if (basePath != null) RulesEngine.loadAll(basePath) else emptyList()
            ctx.rules.clear()
            ctx.rules.addAll(loadedRules)
            if (basePath != null) {
                SkillEngine.startWatching(basePath) { reloadSkills() }
            }
        }
        ctx.systemPrompt = buildSystemPrompt()
    }

    private fun reloadSkills() {
        val basePath = project.basePath ?: return
        val skillDefs = SkillEngine.loadProjectSkills(basePath)
        ctx.skillDefs.clear()
        skillDefs.forEach { ctx.skillDefs[it.name] = it }
        ctx.systemPrompt = buildSystemPrompt()
    }

    private var forkHistory: List<AnthropicMessage>? = null
    var workTreePath: String? = null

    fun run(userMessage: String, apiKey: String, images: List<ImageData>? = null, activatedSkill: String? = null,
            forkHistory: List<AnthropicMessage>? = null, callback: (String, String) -> Unit) {
        this.forkHistory = forkHistory
        cancelled = false
        onStateChange?.invoke(true)
        ctx.activatedSkill = activatedSkill
        ctx.systemPrompt = buildSystemPrompt()
        val history = ctx.conversationHistory

        val t = Thread {
            try {
                com.aiassistant.mcp.McpManager.getInstance(project.basePath)?.healthCheck()

                if (shouldAutoCompact(history)) {
                    AppLogger.info("自动 Compact 触发: historySize=${history.size}")
                    autoCompact(apiKey)
                }

                val toolCount = ctx.toolRegistry.getAll().size
                val toolNames = ctx.toolRegistry.getAll().joinToString(", ") { it.name }
                edt { onMessage?.invoke(AgentMessage("system", "$toolCount 个工具已就绪: $toolNames")) }

                synchronized(ctx.historyLock) {
                    if (forkHistory != null && forkHistory.isNotEmpty()) {
                        val lastRole = forkHistory.last().role
                        if (lastRole == "user") {
                            error("fork 历史末尾为 user 角色，对话消息交替已被破坏，请检查 conversationHistory 构建逻辑")
                        }
                        history.addAll(0, forkHistory)
                    }
                    history.add(AnthropicMessage("user", userMessage, images = images, groupId = roundGroupId))
                }

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
                        // 对齐 Claude Code：子 Agent 结果通过 toolCallId 关联到 task/workflow 工具调用。
                        // LLM 将其视为 tool_result 而非用户指令，消除信任边界混淆。
                        history.add(AnthropicMessage("user", resultText, toolCallId = entry.toolCallId, groupId = roundGroupId))
                    }
                }

                var loopCount = 0
                var consecutiveFailures = 0
                var continuationCount = 0

                while (loopCount < maxLoops && !cancelled) {
                    roundGroupId++
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
                            // 对齐 Claude Code：子 Agent 结果通过 toolCallId 关联到 task/workflow 工具调用
                            history.add(AnthropicMessage("user", text, toolCallId = entry.toolCallId, groupId = roundGroupId))
                            AppLogger.info("AgentLoop: 注入子代理结果 ${entry.id} status=${entry.status} toolCallId=${entry.toolCallId}")
                        }
                    }

                    edt { onThinking?.invoke("思考中...") }

                    val result = callAnthropic(apiKey, history)
                    if (result == null) {
                        edt { onError?.invoke("API 调用失败") }
                        callback("", "")
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
                    } else {
                        // 正常完整回复：重置续写计数
                        continuationCount = 0
                    }
                    edt { onThinking?.invoke(null) }

                    if (toolCalls.isNotEmpty()) {
                        if (thinking.isNotEmpty()) {
                            edt { onMessage?.invoke(AgentMessage("thinking", thinking)) }
                        }
                        if (textContent.isNotEmpty()) {
                            val capturedInputTokens = ctx.lastInputTokens
                            val capturedOutputTokens = ctx.lastOutputTokens
                            edt {
                                onStreaming?.invoke("")
                                onMessage?.invoke(AgentMessage("assistant", textContent,
                                    inputTokens = capturedInputTokens, outputTokens = capturedOutputTokens))
                            }
                        }
                        consecutiveFailures = 0

                        var firstToolCallTextAdded = false
                        var thinkingBlockAdded = false

                        for (tc in toolCalls) {
                            if (cancelled) break

                            // 使用 invokeLater 避免 Agent 线程阻塞等待 EDT，减少工具密集场景的累积延迟
                            edt {
                                onToolExecute?.invoke(tc.name, tc.arguments)
                                onThinking?.invoke("执行 ${tc.name}...")
                            }

                            // ---- Plan Mode: 写工具拦截 ----
                            if (ctx.planMode && tc.name !in SAFE_TOOLS && tc.name !in META_TOOL_NAMES) {
                                val denyMsg = "当前处于规划模式（EnterPlanMode），仅允许只读工具。请先调用 ExitPlanMode 提交方案并获得用户审批后，再执行写操作。"
                                if (!firstToolCallTextAdded) {
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", denyMsg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, denyMsg) }
                                continue
                            }

                            // Skill 元工具
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
                                    history.add(AnthropicMessage("user", errMsg, toolCallId = tc.id, groupId = roundGroupId))
                                    edt { onToolResult?.invoke(tc.name, errMsg) }
                                    continue
                                }
                                val def = ctx.skillDefs[skillName]
                                val skillResult = if (def != null) {
                                    ctx.activatedSkillPrompt = def.prompt
                                    ctx.activatedSkill = skillName
                                    ctx.systemPrompt = buildSystemPrompt()
                                    if (def.preferredModel != null && def.preferredModel != model) {
                                        AppLogger.info("Skill模型路由: '$model' → '${def.preferredModel}' (skill: $skillName)")
                                        model = def.preferredModel!!
                                        edt { onModelRouted?.invoke(def.preferredModel) }
                                    }
                                    "Skill '$skillName' 已激活。${if (!skillArgs.isNullOrBlank()) "用户输入: $skillArgs" else ""}"
                                } else {
                                    ctx.activatedSkill = null
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
                                history.add(AnthropicMessage("user", skillResult, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, skillResult) }
                                continue
                            }

                            // EnterPlanMode — 进入规划模式
                            if (tc.name == "EnterPlanMode") {
                                ctx.planMode = true
                                val msg = "已进入规划模式。现在只能使用只读工具（search_code、read_file、list_directory 等）探索代码库和设计方案。完成后调用 ExitPlanMode 提交方案。"
                                if (!firstToolCallTextAdded) {
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, msg); onTaskUpdate?.invoke() }
                                continue
                            }

                            // ExitPlanMode — 提交规划方案，触发用户审批
                            if (tc.name == "ExitPlanMode") {
                                val planParams = parseParams(tc.arguments)
                                val planText = planParams["plan"]?.takeIf { it.isNotBlank() } ?: "（方案内容为空）"
                                // 提取首行作为标题
                                val title = planText.lines().firstOrNull()?.removePrefix("#")?.trim()?.take(80) ?: "执行计划"
                                var approved = false
                                val latch = CountDownLatch(1)
                                val userChoice = AtomicBoolean(false)
                                pendingConfirmLatch = latch
                                if (cancelled) latch.countDown()
                                try {
                                    onConfirmPlan?.invoke(planText, latch, userChoice)
                                    val confirmed = latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
                                    if (confirmed) approved = userChoice.get()
                                    else consecutiveFailures = MAX_FAILURES
                                } catch (e: Exception) {
                                    if (latch.count > 0) latch.countDown()
                                    if (e is InterruptedException) Thread.currentThread().interrupt()
                                } finally { pendingConfirmLatch = null }
                                if (cancelled) break
                                val planResult = if (approved) {
                                    ctx.planMode = false
                                    ctx.approvedPlanTitle = title
                                    ctx.approvedPlan = planText
                                    edt { onTaskUpdate?.invoke() }
                                    "方案已批准，退出规划模式。现在可以调用 TaskCreate 创建任务并开始执行。"
                                } else {
                                    "方案被用户拒绝。请根据反馈调整方案，或回到规划模式继续研究。"
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
                                history.add(AnthropicMessage("user", planResult, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, planResult) }
                                continue
                            }

                            // TaskCreate — 创建一个任务
                            if (tc.name == "TaskCreate") {
                                val taskParams = parseParams(tc.arguments)
                                val subject = taskParams["subject"]?.takeIf { it.isNotBlank() } ?: "未命名任务"
                                val description = taskParams["description"] ?: ""
                                val nextId = ctx.taskIdCounter.incrementAndGet()
                                val task = Task(id = nextId, subject = subject, description = description)
                                ctx.tasks.add(task)
                                edt { onTaskUpdate?.invoke() }
                                val msg = "任务 #$nextId 已创建: $subject"
                                if (!firstToolCallTextAdded) {
                                    if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                        history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                        thinkingBlockAdded = true
                                    }
                                    history.add(AnthropicMessage("assistant", textContent))
                                    firstToolCallTextAdded = true
                                }
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            // TaskUpdate — 更新任务状态
                            if (tc.name == "TaskUpdate") {
                                val taskParams = parseParams(tc.arguments)
                                val taskId = taskParams["id"]?.toIntOrNull()
                                val status = taskParams["status"]
                                val result = taskParams["result"]
                                val msg = when {
                                    taskId == null -> "更新失败：缺少或无效的 id 参数"
                                    status == null -> "更新失败：缺少 status 参数"
                                    else -> {
                                        val task = ctx.tasks.find { it.id == taskId }
                                        if (task == null) {
                                            "更新失败：任务 #$taskId 不存在，当前共 ${ctx.tasks.size} 个任务"
                                        } else {
                                            when (status) {
                                                "in_progress" -> { task.status = TaskStatus.IN_PROGRESS; edt { onTaskUpdate?.invoke() }; "任务 #$taskId「${task.subject}」开始执行" }
                                                "completed" -> { task.status = TaskStatus.COMPLETED; task.result = result; edt { onTaskUpdate?.invoke() }; "任务 #$taskId「${task.subject}」已完成${if (!result.isNullOrBlank()) ": $result" else ""}" }
                                                else -> "更新失败：无效的 status 值 '$status'，仅支持 in_progress / completed"
                                            }
                                        }
                                    }
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
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            // TaskList — 列出所有任务
                            if (tc.name == "TaskList") {
                                val msg = if (ctx.tasks.isEmpty()) {
                                    "当前没有任务。可调用 TaskCreate 创建新任务。"
                                } else {
                                    val done = ctx.tasks.count { it.status == TaskStatus.COMPLETED }
                                    val total = ctx.tasks.size
                                    "当前任务列表（$done/$total 已完成）：\n" + ctx.tasks.joinToString("\n") { t ->
                                        val mark = when (t.status) { TaskStatus.PENDING -> "☐"; TaskStatus.IN_PROGRESS -> "◉"; TaskStatus.COMPLETED -> "☑" }
                                        "#${t.id} $mark ${t.subject}${if (t.result != null) " — ${t.result}" else ""}"
                                    }
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
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            // TaskGet — 查询单个任务
                            if (tc.name == "TaskGet") {
                                val taskParams = parseParams(tc.arguments)
                                val taskId = taskParams["id"]?.toIntOrNull()
                                val msg = when {
                                    taskId == null -> "查询失败：缺少 id 参数"
                                    else -> {
                                        val task = ctx.tasks.find { it.id == taskId }
                                        if (task == null) "任务 #$taskId 不存在"
                                        else {
                                            val mark = when (task.status) { TaskStatus.PENDING -> "☐ 待处理"; TaskStatus.IN_PROGRESS -> "◉ 进行中"; TaskStatus.COMPLETED -> "☑ 已完成" }
                                            "#$taskId $mark ${task.subject}\n描述: ${task.description.ifBlank { "（无）" }}\n${if (task.result != null) "结果: ${task.result}" else ""}"
                                        }
                                    }
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
                                history.add(AnthropicMessage("user", msg, toolCallId = tc.id, groupId = roundGroupId))
                                edt { onToolResult?.invoke(tc.name, msg) }
                                continue
                            }

                            // ---- 常规工具执行 ----
                            val params = parseParams(tc.arguments).toMutableMap()
                            // 将 tool_use_id 注入参数，供 task/workflow 工具传给 SubAgentRegistry。
                            // 子 Agent 结果通过此 ID 关联到工具调用，对齐 Claude Code 的 tool_result 模式。
                            params["_toolCallId"] = tc.id
                            workTreePath?.let { params["_worktree"] = it }
                            if (tc.name == "task") {
                                params["_forkHistory"] = com.google.gson.Gson().toJson(history.toList())
                            }
                            val readFileOutside = tc.name == "read_file" && params["path"] != null &&
                                !com.aiassistant.shared.PathUtils.isInsideProject(params["path"]!!, project.basePath)
                            // 安全设计（纵深防御）：skill 声明的 allowed-tools 仅对 SAFE_TOOLS 中的只读工具生效。
                            // 写操作（write_file/execute_command/edit_file 等）即使被 skill 声明也必须经用户审批。
                            // 原因：skill 定义来自项目文件（.claude/skills/**/SKILL.md），不可完全信任。
                            // 恶意仓库可通过 skill 声明 allowed-tools: [write_file, execute_command] 绕过审批。
                            val skillAllowed = ctx.skillDefs[ctx.activatedSkill]?.allowedTools?.contains(tc.name) == true
                            // 仅当工具在 SAFE_TOOLS 中时，skill 声明才可免审批；写工具强制走审批流程
                            val skillSafeAllowed = skillAllowed && tc.name in SAFE_TOOLS
                            if (skillAllowed && !skillSafeAllowed) {
                                AppLogger.warn("安全拦截：skill「${ctx.activatedSkill}」尝试在 allowed-tools 中放行危险工具「${tc.name}」，已强制要求用户审批")
                            }
                            val approved = if (!readFileOutside && (tc.name in SAFE_TOOLS || tc.name in AppSettingsService.getInstance().getToolWhitelist() || skillSafeAllowed)) {
                                true
                            } else {
                                val latch = CountDownLatch(1)
                                val userChoice = AtomicBoolean(false)
                                pendingConfirmLatch = latch
                                if (cancelled) latch.countDown()
                                try {
                                    onConfirmTool?.invoke(tc.name, tc.arguments, latch, userChoice)
                                    val confirmed = latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
                                    if (!confirmed) {
                                        consecutiveFailures = MAX_FAILURES
                                        break
                                    }
                                } catch (e: Exception) {
                                    if (latch.count > 0) latch.countDown()
                                    if (e is InterruptedException) Thread.currentThread().interrupt()
                                } finally { pendingConfirmLatch = null }
                                if (cancelled) break
                                userChoice.get()
                            }
                            val toolResult = if (approved) {
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
                                if (!thinkingBlockAdded && thinking.isNotBlank()) {
                                    AppLogger.info("AgentLoop: 添加thinking到history thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                                    history.add(AnthropicMessage("assistant", "", thinking = thinking, thinkingSignature = thinkingSignature))
                                    thinkingBlockAdded = true
                                }
                                history.add(AnthropicMessage("assistant", textContent))
                                firstToolCallTextAdded = true
                            }
                            // 原子化 tool_use + tool_result 对，防止与 clearConversation/compactHistory 的 clear()+addAll() 交叠
                            synchronized(ctx.historyLock) {
                                history.add(AnthropicMessage("assistant", "", toolUseId = tc.id, toolName = tc.name, toolInput = tc.arguments, thinking = thinking, thinkingSignature = thinkingSignature))
                                history.add(AnthropicMessage("user", resultText, toolCallId = tc.id, groupId = roundGroupId))
                            }

                            // 使用 invokeLater 避免 Agent 线程阻塞等待 EDT
                            edt {
                                onToolResult?.invoke(tc.name, resultText)
                                onThinking?.invoke(null)
                            }
                        }

                        if (consecutiveFailures >= MAX_FAILURES) {
                            edt { onError?.invoke("连续失败超过 $MAX_FAILURES 次，已中止") }
                            callback("", "")
                            break
                        }
                        if (truncated && continuationCount >= MAX_CONTINUATIONS) {
                            AppLogger.warn("AgentLoop: 续写次数已达上限 $MAX_CONTINUATIONS（工具调用后），返回已有内容")
                            callback(textContent, thinking)
                            break
                        }
                    } else {
                        if (truncated) {
                            if (thinking.isNotBlank()) {
                                history.add(AnthropicMessage("assistant", textContent, thinking = thinking, thinkingSignature = thinkingSignature))
                            } else {
                                history.add(AnthropicMessage("assistant", textContent))
                            }
                            if (continuationCount >= MAX_CONTINUATIONS) {
                                AppLogger.warn("AgentLoop: 续写次数已达上限 $MAX_CONTINUATIONS，返回已有内容")
                                callback(textContent, thinking)
                                break
                            }
                            AppLogger.info("AgentLoop: max_tokens 截断续写，textLen=${textContent.length}")
                            loopCount++
                            continue
                        }
                        AppLogger.info("AgentLoop 最终回复: $textContent thinkingLen=${thinking.length} sigLen=${thinkingSignature.length}")
                        if (thinking.isNotBlank()) {
                            history.add(AnthropicMessage("assistant", textContent, thinking = thinking, thinkingSignature = thinkingSignature))
                        } else {
                            history.add(AnthropicMessage("assistant", textContent))
                        }
                        if (ctx.goal != null) {
                            val goalAchieved = textContent.contains("目标已达成") ||
                                    textContent.contains("目标完成") ||
                                    textContent.contains("目标已经达成") ||
                                    textContent.contains("目标已达到")
                            if (goalAchieved) {
                                AppLogger.info("AgentLoop: 目标模式检测到目标达成标记，结束循环")
                                callback(textContent, thinking)
                                break
                            }
                            history.add(AnthropicMessage("user",
                                "继续朝着目标「${ctx.goal}」努力。如果目标已达成，请明确说明「目标已达成」并总结完成情况。",
                                groupId = roundGroupId))
                            loopCount++
                            continue
                        }
                        callback(textContent, thinking)
                        break
                    }

                    // 防止溢出：loopCount 达到 Int.MAX_VALUE 后不再递增（2^31-1 轮已远超实际可能）
                    if (loopCount < Int.MAX_VALUE) loopCount++
                }
                if (loopCount >= maxLoops) {
                    AppLogger.warn("AgentLoop: 达到最大轮次 $maxLoops")
                    callback("", "")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                callback("", "")  // 通知调用方（TaskTool 等）执行已被中断
            } catch (e: Exception) {
                AppLogger.error("AgentLoop 异常: ${e.message}\n${e.stackTraceToString()}")
                edt { onError?.invoke("Agent 错误: ${e.message}") }
                callback("", "")
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
        agentThread?.interrupt()
        com.aiassistant.mcp.McpManager.getInstance(project.basePath)?.cancelAll()
        pendingConfirmLatch?.countDown()
        sdkClient?.close()
        SubAgentRegistry.stopAll()
        SkillEngine.stopWatching(project.basePath ?: "")
    }

    /** 等待 agent 线程结束（最多 timeoutMs 毫秒） */
    fun join(timeoutMs: Long) {
        agentThread?.join(timeoutMs)
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
        val taskPrompt = buildTaskPrompt()
        val planModePrompt = buildPlanModePrompt()
        val skillPrompt = ctx.activatedSkillPrompt
        val effectivePrompt = buildString {
            append(ctx.systemPrompt)
            if (skillPrompt != null) { append("\n\n---\n\n## 已激活 Skill\n\n"); append(skillPrompt) }
            if (planModePrompt.isNotEmpty()) { append("\n\n"); append(planModePrompt) }
            if (taskPrompt.isNotEmpty()) { append("\n\n"); append(taskPrompt) }
        }
        val thinkingEnabled = com.aiassistant.AppSettingsService.getInstance().isThinkingEnabled()

        val textBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        val toolCalls = mutableListOf<ToolCallResult>()
        val doneLatch = java.util.concurrent.CountDownLatch(1)
        var hasResponse = false
        var errorDetail: String? = null
        var thinkingSignature = ""
        var capturedStopReason = "end_turn"

        if (sdkClient == null || lastApiKey != apiKey) {
            sdkClient?.close()
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
                override fun onStreamComplete(textContent: String, thinking: String, thinkingSig: String, sdkToolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>, inputTokens: Int, outputTokens: Int, stopReason: String) {
                    ctx.lastInputTokens = inputTokens
                    ctx.lastOutputTokens = outputTokens
                    ctx.tokenStats.totalInput += inputTokens
                    ctx.tokenStats.totalOutput += outputTokens
                    ctx.tokenStats.roundCount++
                    ctx.tokenStats.perRound.add(AgentContext.RoundToken(inputTokens, outputTokens))
                    textBuffer.clear(); textBuffer.append(textContent)
                    thinkingBuffer.clear(); thinkingBuffer.append(thinking)
                    thinkingSignature = thinkingSig
                    capturedStopReason = stopReason
                    sdkToolCalls.forEach { tc ->
                        toolCalls.add(ToolCallResult(tc.id, tc.name, tc.arguments))
                    }
                    AppLogger.info("SDK stream complete: textLen=${textContent.length} thinkingLen=${thinking.length} thinkingSigLen=${thinkingSig.length} tools=${toolCalls.size} stopReason=$stopReason")
                    doneLatch.countDown()
                }
                override fun onError(error: Throwable) {
                    errorDetail = error.message?.take(500) ?: "SDK error: ${error.javaClass.simpleName}"
                    AppLogger.error("SDK streaming error: ${error.message}\n${error.stackTraceToString().take(1000)}")
                    doneLatch.countDown()
                }
            }
        )

        try { doneLatch.await(120, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()  // 恢复中断标志
        }
        if (!hasResponse) {
            // 超时或中断：关闭 SDK 客户端以中断底层 HTTP/SSE 连接，防止资源泄漏
            sdkClient.close()
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

    /** Plan Mode + Task 元工具名称集合（这些元工具在 Plan Mode 中仍然可用） */
    /** Plan Mode + Task 元工具名称（与 buildSdkToolDefs() 共用，单一来源） */
    private val META_TOOL_NAMES = setOf("Skill", "EnterPlanMode", "ExitPlanMode", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet")

    fun compactHistory(keepCount: Int, apiKey: String): String? {
        val history = synchronized(ctx.historyLock) { ctx.conversationHistory.toList() }
        if (history.size <= keepCount) return null

        val historyKeep = history.takeLast(keepCount)
        val historyToSummarize = history.dropLast(keepCount)

        val summary = compact(historyToSummarize, apiKey) ?: return null

        // 设计决策：Compact 摘要注入 system prompt 而非 user 消息。
        // 原因：system prompt 优先级高于 user 消息，摘要作为持久上下文指导 LLM 行为。
        // 这对齐 Claude Code 的 /compact 行为——摘要以系统级指令形式存在。
        // 已知权衡：如果 LLM 在摘要中生成污染内容，可能持久影响后续对话。
        // 缓解措施：摘要由独立 API 调用生成（非用户消息直接触发），且 compact prompt
        // 明确指示"保留关键信息和约定"，降低了恶意摘要生成概率。
        ctx.systemPrompt = buildString {
            append(ctx.systemPrompt)
            append("\n\n## 对话摘要\n以下是之前对话的关键摘要，请基于此继续工作：\n\n")
            append(summary)
        }
        synchronized(ctx.historyLock) {
            ctx.conversationHistory.clear()
            ctx.conversationHistory.addAll(historyKeep)
        }
        ctx.lastInputTokens = 0
        AppLogger.info("Compact 完成: ${historyToSummarize.size} 条 → 1 段摘要, 保留 ${historyKeep.size} 条")
        return summary
    }

    private fun compact(messages: List<AnthropicMessage>, apiKey: String): String? {
        if (messages.isEmpty()) return null
        // 使用独立的 SDK 客户端，避免与 callAnthropic() 共享 sdkClient 导致 stop()/close() 竞态
        val compactClient = com.aiassistant.AnthropicSdkClient(apiKey)

        val compactPrompt = buildString {
            append("你是一个对话摘要助手。请将以下对话历史压缩为一段简洁的中文摘要，")
            append("保留以下关键信息：\n")
            append("1. 用户的原始任务/目标\n")
            append("2. 已完成的关键操作和决策\n")
            append("3. 修改过的文件及原因\n")
            append("4. 遇到的错误及解决方案\n")
            append("5. 当前任务进度（如有）\n")
            append("6. 重要的技术细节和约定\n")
            append("\n请用 200-500 字输出摘要，不要使用标题或列表标记。\n\n")
            append("对话历史：\n")
            for (msg in messages) {
                if (msg.thinking.isNotBlank() && msg.content.isBlank() && msg.toolUseId == null) continue
                val prefix = when {
                    msg.toolCallId != null -> "工具结果"
                    msg.toolUseId != null -> "工具调用(${msg.toolName ?: ""})"
                    msg.role == "user" -> "用户"
                    else -> "AI"
                }
                val content = msg.content.take(2000)
                append("[$prefix] $content\n")
            }
        }

        val summaryHistory = listOf(AnthropicMessage("user", compactPrompt))

        val result = try {
            val compactLatch = java.util.concurrent.CountDownLatch(1)
            var summaryText = ""
            var hasError = false
            compactClient.createStreaming(
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
                    override fun onStreamComplete(textContent: String, thinking: String, thinkingSignature: String, toolCalls: List<com.aiassistant.AnthropicSdkClient.StreamToolCall>, inputTokens: Int, outputTokens: Int, stopReason: String) {
                        summaryText = textContent
                        compactLatch.countDown()
                    }
                    override fun onError(error: Throwable) {
                        hasError = true
                        compactLatch.countDown()
                    }
                }
            )
            if (summaryText.isEmpty() && !hasError) {
                try { compactLatch.await(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            }
            if (hasError || summaryText.isBlank()) null else summaryText.trim()
        } catch (e: Exception) {
            AppLogger.warn("对话压缩失败: ${e.message}")
            null
        } finally {
            compactClient.close()
        }

        return result
    }

    private fun shouldAutoCompact(history: List<AnthropicMessage>): Boolean {
        if (history.size < 30) return false
        val contextWindow = 1_000_000L
        val ratio = com.aiassistant.AppSettingsService.getInstance().getCompactRatio()
        val threshold = (contextWindow * ratio).toLong()
        val tokens = ctx.lastInputTokens
        if (tokens > 0) return tokens > threshold
        val allText = history.joinToString("") { it.content + it.thinking }
        val cjkCount = allText.count { it in '一'..'鿿' }
        val otherCount = allText.length - cjkCount
        val estimatedTokens = (cjkCount * 1.5 + otherCount * 0.25).toLong()
        return estimatedTokens > threshold
    }

    private fun autoCompact(apiKey: String) {
        val summary = compactHistory(30, apiKey) ?: return
        edt { onCompact?.invoke(summary) }
    }

    private var cachedToolDefs: List<com.aiassistant.AnthropicToolDef>? = null
    private var toolDefsHash: Int = 0

    private fun buildSdkToolDefs(): List<com.aiassistant.AnthropicToolDef> {
        val allTools = ctx.toolRegistry.getAll()
        // 用工具列表的 hashCode 判断是否需要重建缓存
        val currentHash = allTools.hashCode()
        if (cachedToolDefs != null && toolDefsHash == currentHash) return cachedToolDefs!!
        toolDefsHash = currentHash

        val builtIn = allTools.map { tool ->
            com.aiassistant.AnthropicToolDef(
                name = tool.name,
                description = tool.description,
                properties = tool.parameters.associate { p ->
                    p.name to com.aiassistant.PropertyDef(p.type, p.description, p.enum)
                }.ifEmpty { null },
                required = tool.parameters.filter { it.required }.map { it.name }.ifEmpty { null }
            )
        }
        cachedToolDefs = if (isSubAgent) builtIn else {
            val metaTools = listOf(
                com.aiassistant.AnthropicToolDef("Skill", "激活一个 skill，获取特定领域的专业指引"),
                com.aiassistant.AnthropicToolDef("EnterPlanMode", "进入规划模式，只允许只读工具"),
                com.aiassistant.AnthropicToolDef("ExitPlanMode", "提交规划方案并请求用户审批"),
                com.aiassistant.AnthropicToolDef("TaskCreate", "创建一个执行任务"),
                com.aiassistant.AnthropicToolDef("TaskUpdate", "更新任务状态"),
                com.aiassistant.AnthropicToolDef("TaskList", "列出所有任务"),
                com.aiassistant.AnthropicToolDef("TaskGet", "查询任务详情")
            )
            builtIn + metaTools
        }
        return cachedToolDefs!!
    }

    private val gson = com.google.gson.Gson()  // 复用实例，避免每次 parseParams 重建

    private fun parseParams(json: String): Map<String, String> {
        return try {
            val raw = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return emptyMap()
            raw.mapNotNull { (k, v) ->
                if (k == null) return@mapNotNull null
                val value = when (v) {
                    is String -> v
                    null -> ""
                    else -> gson.toJson(v)
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
        val activeSkill = ctx.activatedSkill
        val availableSkills = ctx.skillDefs.values.filter { it.name != activeSkill }
        val skillsSection = if (availableSkills.isNotEmpty()) {
            "\n## Skills\n" + availableSkills.joinToString("\n") { s ->
                "- **${s.name}**: ${s.description}"
            } + "\n\n可通过 /skill名称 激活 Skill，或调用 **Skill** 工具动态激活。"
        } else ""
        val promptsSection = if (ctx.mcpPrompts.isNotEmpty()) {
            "\n## MCP Prompts\n" + ctx.mcpPrompts.joinToString("\n") { p ->
                val argsStr = if (p.arguments.isNotEmpty()) "（参数: ${p.arguments.joinToString { "${it.name}:${it.type}" }}）" else ""
                "- **${p.name}**$argsStr: ${p.description}"
            } + "\n\n需要时可通过 **mcp_get_prompt** 工具获取完整渲染内容（传入 name 和可选的 arguments JSON）。"
        } else ""
        val resourcesSection = if (ctx.mcpResources.isNotEmpty()) {
            "\n## MCP Resources\n" + ctx.mcpResources.joinToString("\n") { r ->
                "- `${r.uri}` — ${r.name}${if (r.description.isNotBlank()) ": ${r.description}" else ""}"
            } + "\n\n可通过 **read_file** 工具读取上述 MCP 资源（传入 resource:// URI 作为 path 参数）。"
        } else ""
        val goalSection = if (ctx.goal != null) {
            "\n## 🎯 当前目标\n你的任务是持续工作直到达成以下目标——不要提前结束，使用工具不断尝试直到成功。如果目标已达成，请明确告知用户。\n**目标：${ctx.goal}**\n"
        } else ""
        val rulesSection = buildRulesSection()
        val claudeMdContent = loadClaudeMdFiles()
        return """
You are an AI coding assistant in idea. Use tools to work with the project.

## Project: ${project.name}
Path: ${project.basePath ?: "unknown"}

## Tools
$toolList
$goalSection
$rulesSection
## Planning
对于需要多步骤完成的复杂任务（如实现功能、重构代码、架构变更），你应该：
1. 先调用 **EnterPlanMode** 进入规划模式，探索代码库、设计方案
2. 完成研究后调用 **ExitPlanMode**（plan=方案内容）提交审批
3. 用户批准后，用 **TaskCreate** 创建可追踪的任务，然后逐个执行
4. 开始任务时调用 **TaskUpdate**（status="in_progress"），完成后调用（status="completed" + result）
5. 需要查看全部任务状态时调用 **TaskList**，查看单个任务详情时调用 **TaskGet**
简单任务（读取文件、回答单个问题、一行修改）不要进入规划模式，直接处理即可。
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

    private fun buildRulesSection(): String {
        val allRules = ctx.rules
        if (allRules.isEmpty()) return ""
        val basePath = project.basePath ?: ""
        val openedFiles = try {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                .openFiles.mapNotNull { f ->
                    val abs = f.path
                    if (abs.startsWith(basePath)) abs.removePrefix(basePath).removePrefix("/") else null
                }
        } catch (_: Exception) { emptyList() }
        val matched = allRules.filter { rule ->
            if (rule.paths == null) true
            else openedFiles.any { f -> RulesEngine.matchesFile(rule, f) }
        }
        if (matched.isEmpty()) return ""
        return "\n## 项目规则\n" + matched.joinToString("\n\n") { r ->
            "### ${r.description}\n${r.content}"
        } + "\n"
    }

    private fun loadClaudeMdFiles(): String {
        val parts = mutableListOf<String>()
        val basePath = project.basePath ?: return ""
        val home = System.getProperty("user.home") ?: return ""

        val userGlobal = java.io.File(home, ".claude/CLAUDE.md")
        if (userGlobal.exists()) {
            try { parts.add(userGlobal.readText()) } catch (_: Exception) {}
        }

        val projectRoot = java.io.File(basePath, "CLAUDE.md")
        if (projectRoot.exists()) {
            try { parts.add(projectRoot.readText()) } catch (_: Exception) {}
        }

        val dotClaude = java.io.File(basePath, ".claude/CLAUDE.md")
        if (dotClaude.exists()) {
            try { parts.add(dotClaude.readText()) } catch (_: Exception) {}
        }

        val localMd = java.io.File(basePath, "CLAUDE.local.md")
        if (localMd.exists()) {
            try { parts.add(localMd.readText()) } catch (_: Exception) {}
        }

        if (parts.isEmpty()) return ""
        return "\n## CLAUDE.md\n\n${parts.joinToString("\n\n---\n\n")}"
    }

    /** 动态生成当前任务状态提示，每次 API 调用前注入，确保 LLM 不会忘记未完成的任务 */
    private fun buildTaskPrompt(): String {
        val tasks = ctx.tasks
        if (tasks.isEmpty()) return ""
        val done = tasks.count { it.status == TaskStatus.COMPLETED }
        if (done == tasks.size) return ""  // 全部完成
        val inProgress = tasks.count { it.status == TaskStatus.IN_PROGRESS }
        val pending = tasks.count { it.status == TaskStatus.PENDING }
        val tasksSummary = tasks.joinToString("\n") { t ->
            val mark = when (t.status) { TaskStatus.PENDING -> "⏳"; TaskStatus.IN_PROGRESS -> "🔄"; TaskStatus.COMPLETED -> "✅" }
            "#${t.id} $mark ${t.subject}${if (t.description.isNotBlank()) " — ${t.description}" else ""}"
        }
        return """
## 当前任务
进度: $done/${tasks.size} 已完成（$inProgress 进行中, $pending 待处理）

$tasksSummary

你需要继续执行未完成的任务：
- 调用 TaskUpdate(id=<id>, status="in_progress") 开始一个任务
- 完成后调用 TaskUpdate(id=<id>, status="completed", result="...")
- 调用 TaskList 查看全部任务状态
- 只在所有任务完成后才输出最终文字
""".trimIndent()
    }

    /** 动态生成规划模式提示 */
    private fun buildPlanModePrompt(): String {
        if (!ctx.planMode) return ""
        return """
## 🔒 规划模式
你当前处于规划模式。只能使用只读工具（search_code、read_file、list_directory、git_diff 等）探索代码库和设计方案。
禁止使用任何写操作工具（write_file、edit_file、execute_command 等）。
完成研究后，调用 ExitPlanMode(plan="方案内容") 提交方案供用户审批。
""".trimIndent()
    }

    private fun edt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action)
    }
}
