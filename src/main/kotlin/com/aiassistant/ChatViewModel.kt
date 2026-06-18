package com.aiassistant

import com.aiassistant.agent.AgentContext
import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentMessage
import com.aiassistant.agent.ImageData
import com.aiassistant.agent.Task
import com.aiassistant.agent.TaskStatus
import com.aiassistant.mcp.McpManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * v3 UI 桥接 — 轻量 ViewModel，委托给 AgentLoop。
 */
data class ApprovalState(val latch: java.util.concurrent.CountDownLatch, val userChoice: java.util.concurrent.atomic.AtomicBoolean)

class ChatViewModel {
    @Volatile var messages = mutableListOf<AgentMessage>()
    val messageCount: Int get() = messages.size
    @Volatile var streamingContent = ""
    @Volatile var streamingThinking = ""
    @Volatile var isStreaming = false
    @Volatile var isRateLimited = false
    /** 防止 compact 重复调用（两次 /compact 创建多个后台压缩线程） */
    private val isCompacting = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 限流恢复 Timer 引用，供 stopGeneration/clearConversation 取消 */
    private var rateLimitTimer: javax.swing.Timer? = null
    @Volatile var currentToolName: String? = null
    /** 待审批工具: toolName → ApprovalState */
    @Volatile var pendingApprovals = mutableMapOf<String, ApprovalState>()
    /** 消息关联的引用 chips，key=msgId。sendMessage 时在 onMessagesChanged 之前写入 */
    val messageRefChips = mutableMapOf<Long, List<com.aiassistant.ChatToolWindow.RefChip>>()
    /**
     * Agent 当前活动状态 —— 单点表达 UI 指示器该显示什么，
     * 取代以前用 currentToolName 一字段三义（工具名/思考文本/null）+ 字符串嗅探，
     * 那是"执行中指示器闪烁"的根因。
     */
    @Volatile var activity: Activity = Activity.Idle
    @Volatile var tasks: List<Task> = emptyList()  // 任务列表（TaskCreate/TaskUpdate 触发更新）
    @Volatile var planMode: Boolean = false       // 规划模式中
    @Volatile var approvedPlanTitle: String? = null  // 已批准计划标题
    @Volatile var approvedPlan: String? = null       // 已批准计划文本
    @Volatile var currentGoal: String? = null  // 目标模式：非空时 UI 显示 GoalBar
    @Volatile var goalRound: Int = 0           // 目标模式：当前轮次计数
    @Volatile var currentModel: String = "deepseek-chat"
    /** Agent 当前是否在思考中（由 onThinkingDelta 直接设置，避免字符串嗅探语言依赖） */
    @Volatile var isThinking = false
    /** 每次 sendMessage/stopGeneration 递增，用于回调中校验是否已被新请求覆盖 */
    @Volatile private var generationId = 0L

    /** Agent 活动状态。 */
    sealed class Activity {
        /** 空闲（无指示器）。 */
        object Idle : Activity()
        /** 思考中（模型推理、尚未产生输出或工具调用）。 */
        object Thinking : Activity()
        /** 正在执行某个工具。 */
        data class RunningTool(val toolName: String) : Activity()
    }

    var onMessagesChanged: (() -> Unit)? = null
    var onStreamingUpdate: ((String) -> Unit)? = null
    var onStreamingThinkingChanged: ((String) -> Unit)? = null
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String?) -> Unit)? = null
    var onRateLimitCountdown: ((Int) -> Unit)? = null
    var onToolExecute: ((String, String) -> Unit)? = null
    var onToolResult: ((String, String) -> Unit)? = null
    var onToolStreaming: ((String, String) -> Unit)? = null  // 工具执行期间的中间输出
    var onTaskUpdate: (() -> Unit)? = null
    var onGoalUpdate: ((String, Int) -> Unit)? = null  // 目标模式轮次更新：(goal, roundNumber)
    var onModelRouted: ((String) -> Unit)? = null
    var onConfirmTool: ((String, String, CountDownLatch, AtomicBoolean) -> Unit)? = null
    var onConfirmPlan: ((String, CountDownLatch, AtomicBoolean) -> Unit)? = null  // Plan Mode 审批
    var onThinkingContent: ((String) -> Unit)? = null

    private var agent: AgentLoop? = null
    private var project: Project? = null

    fun initialize(project: Project, mcpTools: List<com.aiassistant.agent.AgentTool> = emptyList()) {
        this.project = project
        val a = AgentLoop(project)
        a.initialize(mcpTools)
        setupCallbacks(a)
        agent = a
    }

    data class SkillInfo(val name: String, val description: String)

    fun getSkillNames(): List<String> = getSkills().map { it.name }

    /** 获取所有 skill 的名称和描述（用于 UI 菜单展示） */
    /** UI 菜单中的 skill 列表：过滤掉 invoke-for="agent" 的（仅 LLM 可调用） */
    fun getSkills(): List<SkillInfo> = agent?.ctx?.skillDefs?.values
        ?.filter { it.invokeFor != "agent" }
        ?.map { SkillInfo(it.name, it.description) }
        ?: emptyList()

    fun getTokenStats(): AgentContext.TokenStats? = agent?.ctx?.tokenStats

    fun addMcpTools(mcpTools: List<com.aiassistant.agent.AgentTool>) {
        agent?.ctx?.toolRegistry?.registerMcp(mcpTools)
    }

    /** 添加 MCP prompts 到 Agent 上下文（供 system prompt 注入） */
    fun addMcpPrompts(prompts: List<com.aiassistant.mcp.McpPromptDef>) {
        agent?.ctx?.mcpPrompts?.addAll(prompts)
    }

    /** 添加 MCP resources 到 Agent 上下文（供 system prompt 注入） */
    fun addMcpResources(resources: List<com.aiassistant.mcp.McpResourceDef>) {
        agent?.ctx?.mcpResources?.addAll(resources)
    }

    /** 注册 MCP 变更监听器（对齐 Claude Code：服务器推送变更时自动更新 Agent 上下文和工具注册中心） */
    fun setupMcpChangeListener(mcpManager: com.aiassistant.mcp.McpManager) {
        mcpManager.changeListener = object : com.aiassistant.mcp.McpChangeListener {
            override fun onToolsChanged(serverName: String, newTools: List<com.aiassistant.agent.AgentTool>) {
                val a = agent ?: return
                a.ctx.toolRegistry.replaceMcp(newTools)
            }
            override fun onPromptsChanged(newPrompts: List<com.aiassistant.mcp.McpPromptDef>) {
                val ctx = agent?.ctx ?: return
                ctx.mcpPrompts.clear()
                ctx.mcpPrompts.addAll(newPrompts)
            }
            override fun onResourcesChanged(newResources: List<com.aiassistant.mcp.McpResourceDef>) {
                val ctx = agent?.ctx ?: return
                ctx.mcpResources.clear()
                ctx.mcpResources.addAll(newResources)
            }
        }
    }

    /** 添加 thinking 消息，若上一条也是 thinking 则合并，避免多个相邻思考行 */
    private fun addThinkingMessage(content: String) {
        val last = messages.lastOrNull()
        if (last != null && last.role == "thinking") {
            messages[messages.lastIndex] = last.copy(content = last.content + "\n" + content, version = last.version + 1)
        } else {
            messages.add(AgentMessage("thinking", content))
        }
        streamingThinking = ""
    }

    private fun setupCallbacks(a: AgentLoop) {
        a.onMessage = { msg ->
            runOnEdt {
                AppLogger.info("EDT onMessage role=${msg.role} contentLen=${msg.content.length} streamingThinking.len=${streamingThinking.length} streamingContent.len=${streamingContent.length}")
                if (msg.role == "thinking") {
                    addThinkingMessage(msg.content)
                } else {
                    messages.add(msg)
                    // 目标模式下，每收到 assistant 回复递增轮次
                    if (msg.role == "assistant" && currentGoal != null) {
                        goalRound++
                        onGoalUpdate?.invoke(currentGoal!!, goalRound)
                    }
                }
                onMessagesChanged?.invoke()
            }
        }
        a.onStreaming = { text ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt  // 已被 stop/新请求覆盖
                streamingContent = text; onStreamingUpdate?.invoke(text)
            }
        }
        a.onThinkingDelta = { text ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                isThinking = true
                streamingThinking = text; onStreamingThinkingChanged?.invoke(text)
            }
        }
        a.onToolStreaming = { name, partial ->
            runOnEdt {
                onToolStreaming?.invoke(name, partial)
            }
        }
        a.onToolExecute = { name, args ->
            runOnEdt {
                activity = Activity.RunningTool(name)
                currentToolName = name
                messages.add(AgentMessage("tool", args, toolName = name))
                onToolExecute?.invoke(name, args); onMessagesChanged?.invoke()
            }
        }
        a.onToolResult = { name, result ->
            runOnEdt {
                // 工具结束后 agent 仍在循环 → 回到"思考中"而非 Idle，避免指示器消失再出现的闪烁
                activity = Activity.Thinking
                currentToolName = null
                // 找到对应的 tool 消息（由 onToolExecute 插入），追加结果并清除审批状态
                val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(content = messages[idx].content + "\n---\n" + result, approvalPending = false, version = messages[idx].version + 1)
                } else {
                    messages.add(AgentMessage("tool", result, toolName = name))
                }
                pendingApprovals.remove(name)  // 审批已完成，清理状态
                onToolResult?.invoke(name, result); onMessagesChanged?.invoke()
            }
        }
        a.onTaskUpdate = { runOnEdt {
            tasks = a.ctx.tasks.toList()
            planMode = a.ctx.planMode
            approvedPlanTitle = a.ctx.approvedPlanTitle
            approvedPlan = a.ctx.approvedPlan
            onTaskUpdate?.invoke(); onMessagesChanged?.invoke()
        } }
        a.onConfirmPlan = { planText, latch, userChoice -> runOnEdt { onConfirmPlan?.invoke(planText, latch, userChoice) } }
        a.onError = { msg ->
            runOnEdt {
                // 清理流式状态，防止残留的 streaming bubble 与错误消息重复渲染
                isStreaming = false
                streamingContent = ""
                streamingThinking = ""
                isThinking = false
                activity = Activity.Idle
                // Rate Limit 检测：若错误消息包含 429 状态码，标记限流状态并在若干秒后自动恢复
                if (msg.contains("429")) {
                    isRateLimited = true
                    onError?.invoke(msg)
                    // 60 秒后用 javax.swing.Timer 在 EDT 上自动重置限流状态
                    rateLimitTimer?.stop()
                    rateLimitTimer = javax.swing.Timer(60_000) { isRateLimited = false }.apply {
                        isRepeats = false
                        start()
                    }
                } else {
                    onError?.invoke(msg)
                }
                // 触发 UI 重建，移除残留的流式组件
                onMessagesChanged?.invoke()
            }
        }
        a.onStateChange = { streaming ->
            runOnEdt {
                isStreaming = streaming
                // 运行开始→思考中；结束→空闲，清理所有流式状态防止残留渲染
                if (streaming) { if (activity == Activity.Idle) activity = Activity.Thinking }
                else { activity = Activity.Idle; currentToolName = null; streamingThinking = ""; streamingContent = ""; isThinking = false; autoSaveSession() }
                onStreamingStateChanged?.invoke(streaming)
            }
        }
        a.onThinking = { text ->
            runOnEdt {
                // 仅"思考中"语义更新状态；null（清空）不再直接清状态，避免 null 间隙闪烁。
                // 不触发 onMessagesChanged，否则当前轮 streamingContent 还未清空时就 rebuild，
                // 导致同一份 AI 回复先作为流式气泡渲染一次，又被 callback 的 messages 渲染一次（重复显示）。
                // 使用 isThinking 标记而非 text.contains("思考")，避免中文语言依赖。
                if (text != null && isThinking) {
                    activity = Activity.Thinking
                }
            }
        }
        a.onModelRouted = { model -> runOnEdt { currentModel = model } }
        a.onConfirmTool = { name, args, latch, result ->
            runOnEdt {
                pendingApprovals[name] = ApprovalState(latch, result)
                val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name }
                if (idx >= 0) messages[idx] = messages[idx].copy(approvalPending = true, version = messages[idx].version + 1)
                onConfirmTool?.invoke(name, args, latch, result)
            }
        }
        a.onCompact = { summary ->
            runOnEdt {
                val keep = messages.takeLast(8).toList()
                messages.clear()
                messages.add(AgentMessage("system", "📋 对话摘要：$summary"))
                messages.addAll(keep)
                onMessagesChanged?.invoke()
            }
        }
    }

    /**
     * @param content 用户输入文本（显示在气泡中）
     * @param images 粘贴的图片
     * @param refContent 文件引用的 Markdown 内容（仅发给 LLM，不显示在气泡中）
     */
    /** 发送用户消息，返回消息 ID（用于 messageRefChips 索引） */
    fun sendMessage(apiKey: String, content: String, images: List<ImageData>? = null, refContent: String = "", refChips: List<com.aiassistant.ChatToolWindow.RefChip> = emptyList()): Long {
        if ((content.isBlank() && images.isNullOrEmpty()) || isStreaming || isRateLimited) return -1L
        generationId++  // 新轮次，DD旧回调
        streamingContent = ""
        streamingThinking = ""

        // 对齐 Claude Code：客户端拦截 /skill-name，注入 prompt 并从工具列表移除该 skill
        val resolved = resolveSkillInvocation(content)

        // 气泡只显示用户可见文本（不含 skill prompt），引用内容以 chips 形式独立展示
        val msg = AgentMessage("user", resolved.displayContent, images = images)
        messages.add(msg)
        if (refChips.isNotEmpty()) {
            messageRefChips[msg.id] = refChips
        }
        runOnEdt { onMessagesChanged?.invoke() }
        isStreaming = true
        // 立即显示"等待AI回复"指示器，消除用户发送消息后到首个 streaming 事件之间的空白等待感
        streamingThinking = "等待 AI 回复..."
        activity = Activity.Thinking
        runOnEdt {
            onStreamingThinkingChanged?.invoke(streamingThinking)
            onStreamingStateChanged?.invoke(true)
        }

        val llmContent = if (refContent.isNotEmpty()) "${resolved.llmContent}\n\n$refContent" else resolved.llmContent
        AppLogger.info("用户消息: ${resolved.displayContent}")
        val a = agent
        if (a != null) {
            // Skill 模型路由：客户端激活时应用 preferredModel
            if (resolved.preferredModel != null) {
                a.switchModel(resolved.preferredModel!!)
            }
            // 对齐 Claude Code：skill prompt 注入 system prompt
            a.ctx.activatedSkillPrompt = resolved.skillPrompt
            a.run(llmContent, apiKey, images, resolved.skillName) { finalText, thinking ->
                // thinking 与 assistant 消息在同一个 EDT 块中原子性地落地，
                // 同时清空 streamingThinking/streamingContent，避免分两次 rebuild
                // 导致 streamingContent 作为临时气泡重复渲染。
                runOnEdt {
                    // 先清空流式状态并标记流式结束——防止 SDK 延迟到达的 onTextDelta
                    // EDT 回调在 rebuild 之后重新设置 streamingContent，导致
                    // updateStreamingBubble 又创建一个流式气泡与正式消息重复。
                    isStreaming = false
                    streamingContent = ""
                    streamingThinking = ""
                    if (thinking.isNotEmpty()) {
                        addThinkingMessage(thinking)
                    }
                    if (finalText.isNotEmpty()) {
                        AppLogger.info("收到AI回复: $finalText")
                        messages.add(AgentMessage("assistant", finalText))
                    }
                    activity = Activity.Idle
                    onMessagesChanged?.invoke()
                    // 手动触发状态回调（AgentLoop finally 块也会触发，此处先触发确保输入框尽早恢复）
                    onStreamingStateChanged?.invoke(false)
                }
            }
        } else {
            // agent 未初始化（initialize() 未被调用），恢复状态并向用户展示错误
            isStreaming = false
            streamingContent = ""
            runOnEdt {
                messages.add(AgentMessage("assistant", "Agent 未初始化，请重新打开对话窗口"))
                activity = Activity.Idle
                onMessagesChanged?.invoke()
            }
        }
        return msg.id
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
            return
        }
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(action) else action()
    }

    /** 刷新 Agent 模型配置，响应 Settings 变更 */
    fun refreshModel() {
        agent?.refreshModel()
    }

    /** resolveSkillInvocation 的返回值 */
    private data class SkillResolution(
        val displayContent: String,
        val llmContent: String,
        val skillName: String?,
        val preferredModel: String?,
        val skillPrompt: String? = null  // 注入 system prompt，对齐 Claude Code
    )

    /**
     * 对齐 Claude Code：客户端拦截 /skill-name，将 skill prompt 注入 LLM 上下文。
     * 同时提取 preferredModel 用于模型路由。
     */
    private fun resolveSkillInvocation(content: String): SkillResolution {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("/")) return SkillResolution(content, content, null, null)
        val skillName = trimmed.removePrefix("/").substringBefore(" ").substringBefore("\n")
        if (skillName.isBlank()) return SkillResolution(content, content, null, null)
        // 对齐 Claude Code：从 skillDefs 查找（skill 不再注册为独立工具）
        val def = agent?.ctx?.skillDefs?.get(skillName) ?: return SkillResolution(content, content, null, null)
        // 用户可见内容：保留 /skill-name 前缀，让用户知道激活了哪个 skill
        val userInput = trimmed.removePrefix("/$skillName").trimStart()
        val displayContent = if (userInput.isNotEmpty()) "/$skillName $userInput" else "/$skillName"
        // 对齐 Claude Code：skill prompt 注入 system prompt（指令级），不混入用户消息
        // llmContent 只含用户输入，skill prompt 通过 AgentContext.activatedSkillPrompt 传递
        val llmContent = userInput.ifEmpty { "执行此 skill" }
        AppLogger.info("Skill客户端拦截: /$skillName → prompt注入system (${def.prompt.length} chars) model=${def.preferredModel}")
        return SkillResolution(
            displayContent = displayContent,
            llmContent = llmContent,
            skillName = skillName,
            preferredModel = def.preferredModel,
            skillPrompt = def.prompt
        )
    }

    fun stopGeneration() {
        generationId++  // 废弃所有 pending 回调
        rateLimitTimer?.stop()
        agent?.stop()
        isStreaming = false
        streamingContent = ""
        streamingThinking = ""
        runOnEdt { onStreamingStateChanged?.invoke(false) }
    }

    fun clearConversation() {
        stopGeneration()  // 内部已调用 rateLimitTimer?.stop()
        agent?.ctx?.let { ctx ->
            synchronized(ctx.historyLock) { ctx.conversationHistory.clear() }
            ctx.lastInputTokens = 0
            ctx.goal = null  // 清空目标
        }
        messages.clear()
        streamingContent = ""
        streamingThinking = ""
        tasks = emptyList()
        planMode = false
        approvedPlanTitle = null
        approvedPlan = null
        currentGoal = null
        goalRound = 0
        lastSavedCount = 0
        autoSaveSessionId = java.util.UUID.randomUUID().toString().take(8)  // /clear 后开启新会话
        isRateLimited = false
        pendingApprovals.clear()
        activity = Activity.Idle
        isThinking = false
        runOnEdt { onMessagesChanged?.invoke() }
    }

    /** 设置目标驱动模式 */
    fun setGoal(goal: String) {
        agent?.ctx?.goal = goal
        currentGoal = goal
        goalRound = 0
    }

    /** 清除目标 */
    fun clearGoal() {
        agent?.ctx?.goal = null
        currentGoal = null
        goalRound = 0
    }

    /**
     * 压缩对话历史：将旧消息替换为 LLM 生成的摘要，释放 token 预算。
     * 对齐 Claude Code /compact：保留最近 8 条消息，其余压缩为一段摘要。
     * 同步更新跨轮历史（ctx.conversationHistory）和 UI 消息列表（messages）。
     */
    fun compactConversation(apiKey: String) {
        if (!isCompacting.compareAndSet(false, true)) return
        val a = agent ?: run { isCompacting.set(false); return }
        val recentMessages = messages.toList().takeLast(15)
        stopGeneration()  // 防止 compact 与 agent 循环并发使用 sdkClient
        Thread {
            try {
                val summary = a.compactHistory(15, apiKey)
                if (summary != null) {
                    runOnEdt {
                        messages.clear()
                        messages.add(AgentMessage("system", "📋 对话摘要：$summary"))
                        messages.addAll(recentMessages)
                        onMessagesChanged?.invoke()
                    }
                }
            } finally { isCompacting.set(false) }
        }.start()
    }

    // ---- 会话持久化（增量追加）----

    private var autoSaveSessionId: String = java.util.UUID.randomUUID().toString().take(8)
    @Volatile private var lastSavedCount: Int = 0  // EDT 读 + 后台 Thread 写，需 @Volatile

    fun autoSaveSession() {
        val path = project?.basePath ?: return
        if (messages.isEmpty()) return
        val currentSize = messages.size
        if (currentSize == lastSavedCount) return  // 无新消息，跳过
        val snapshot = messages.toList()
        val sessionId = autoSaveSessionId
        val savedCount = currentSize
        val stats = agent?.ctx?.tokenStats
        val statsDTO = stats?.let {
            com.aiassistant.session.SessionStore.TokenStatsDTO(
                totalInput = it.totalInput, totalOutput = it.totalOutput, roundCount = it.roundCount,
                perRound = it.perRound.map { r ->
                    com.aiassistant.session.SessionStore.RoundTokenDTO(r.inputTokens, r.outputTokens, r.timestamp)
                }
            )
        }
        Thread {
            val name = snapshot.firstOrNull { it.role == "user" }?.content?.take(50) ?: "空会话"
            com.aiassistant.session.SessionStore.save(path, sessionId, name, snapshot, statsDTO)
            lastSavedCount = savedCount
        }.start()
    }

    fun loadSession(id: String) {
        val data = project?.basePath?.let { com.aiassistant.session.SessionStore.load(it, id) } ?: return
        clearConversation()
        messages.addAll(data.messages.mapNotNull { dto ->
            when (dto.role) {
                "user" -> AgentMessage("user", dto.content)
                "assistant" -> AgentMessage("assistant", dto.content)
                else -> null
            }
        })
        // 恢复 token 统计数据
        data.tokenStats?.let { ts ->
            agent?.ctx?.tokenStats?.apply {
                totalInput = ts.totalInput
                totalOutput = ts.totalOutput
                roundCount = ts.roundCount
                perRound.clear()
                ts.perRound.forEach { r ->
                    perRound.add(com.aiassistant.agent.AgentContext.RoundToken(r.inputTokens, r.outputTokens, r.timestamp))
                }
            }
        }
        lastSavedCount = messages.size  // 恢复后以此为基准，只追加新消息
        runOnEdt { onMessagesChanged?.invoke() }
    }

    fun listSessions(): List<com.aiassistant.session.SessionStore.SessionMeta> =
        project?.basePath?.let { com.aiassistant.session.SessionStore.list(it) } ?: emptyList()
}
