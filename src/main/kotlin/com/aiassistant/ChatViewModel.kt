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
 * UI 桥接 — 轻量 ViewModel，委托给 AgentLoop。
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
    /** 防止并发提取记忆 */
    private val extractingMemory = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 限流恢复 Timer 引用，供 stopGeneration/clearConversation 取消 */
    private var rateLimitTimer: javax.swing.Timer? = null
    @Volatile var currentToolName: String? = null
    /** 待审批工具: toolName → ApprovalState（ConcurrentHashMap 线程安全） */
    val pendingApprovals = java.util.concurrent.ConcurrentHashMap<String, ApprovalState>()
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

    /** 流式 assistant 消息 ID（单管线——流式增量原地更新此消息，不另存 streamingContent） */
    private var streamingAssistantMsgId: Long = -1L

    /** 流式 thinking 消息 ID（同上） */
    private var streamingThinkingMsgId: Long = -1L

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
    var onThinkingCompleted: (() -> Unit)? = null  // 思考流式结束，通知 UI 固化行（改标题"思考中..."→"思考过程"）
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

    private var agent: AgentLoop? = null
    private var project: Project? = null

    fun setHookEventBus(bus: com.aiassistant.hooks.HookEventBus) {
        agent?.ctx?.hookEventBus = bus
    }

    fun initialize(project: Project, mcpTools: List<com.aiassistant.agent.AgentTool> = emptyList()) {
        this.project = project
        val a = AgentLoop(project)
        a.initialize(mcpTools)
        setupCallbacks(a)
        agent = a

        // SessionStart hook
        val hookBus = a.ctx.hookEventBus
        if (hookBus != null) {
            hookBus.fire("SessionStart", mapOf("project_dir" to project.basePath))
        }
    }

    data class SkillInfo(val name: String, val description: String)

    fun getSkillNames(): List<String> = getSkills().map { it.name }

    fun getSkills(): List<SkillInfo> = agent?.ctx?.skillDefs?.values
        ?.filter { it.invokeFor != "agent" }
        ?.map { SkillInfo(it.name, it.description) }
        ?: emptyList()

    /** 手动重载 skills（/reload-skills 命令触发） */
    fun reloadSkills() { agent?.reloadSkills() }

    fun getTokenStats(): AgentContext.TokenStats? = agent?.ctx?.tokenStats

    /** 提供对 memoryEngine 的访问，供 /memory 命令使用 */
    fun getMemoryEngine(): com.aiassistant.agent.memory.MemoryEngine? = agent?.ctx?.memoryEngine

    fun getApiKey(): String? = com.aiassistant.AppSettingsService.getInstance().getApiKey()

    fun addSystemMessage(text: String) {
        messages.add(AgentMessage("system", text))
        onMessagesChanged?.invoke()
    }

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
    /** 获取或创建流式 assistant 消息（单管线：流式增量原地更新消息 content） */
    private fun getOrCreateStreamingAssistant(): AgentMessage {
        val idx = messages.indexOfLast { it.id == streamingAssistantMsgId }
        if (idx >= 0) return messages[idx]
        val msg = AgentMessage(
            "assistant",
            "",
            status = AgentMessage.MessageStatus.STREAMING,
            inputTokens = 0,
            outputTokens = 0
        )
        messages.add(msg)
        streamingAssistantMsgId = msg.id
        return msg
    }

    /** 完成流式 assistant 消息（标记为 NORMAL） */
    private fun finalizeStreamingAssistant(
        textContent: String,
        inputTokens: Int,
        outputTokens: Int
    ) {
        val idx = messages.indexOfLast { it.id == streamingAssistantMsgId }
        if (idx >= 0 && textContent.isNotEmpty()) {
            messages[idx] = messages[idx].copy(
                content = textContent,
                status = AgentMessage.MessageStatus.NORMAL,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                version = messages[idx].version + 1
            )
        } else if (idx < 0 && textContent.isNotEmpty()) {
            messages.add(
                AgentMessage(
                    "assistant",
                    textContent,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens
                )
            )
        }
        streamingAssistantMsgId = -1L
    }

    /** 添加 thinking 消息（单管线：首次创建 STREAMING，后续原地更新 content，完成时标记 NORMAL） */
    private fun upsertThinkingMessage(content: String, done: Boolean = false) {
        val idx = messages.indexOfLast { it.id == streamingThinkingMsgId }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(
                content = content,
                version = messages[idx].version + 1,
                status = if (done) AgentMessage.MessageStatus.NORMAL else AgentMessage.MessageStatus.STREAMING
            )
        } else {
            val msg = AgentMessage(
                "thinking",
                content,
                status = if (done) AgentMessage.MessageStatus.NORMAL else AgentMessage.MessageStatus.STREAMING
            )
            messages.add(msg)
            streamingThinkingMsgId = msg.id
        }
        if (done) {
            streamingThinkingMsgId = -1L
            onThinkingCompleted?.invoke()
        }
    }

    private fun setupCallbacks(a: AgentLoop) {
        a.onMessage = { msg ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                if (msg.role == "thinking") {
                    upsertThinkingMessage(msg.content, done = true)
                } else {
                    messages.add(msg)
                    if (msg.role == "assistant" && currentGoal != null) {
                        goalRound++; onGoalUpdate?.invoke(currentGoal!!, goalRound)
                    }
                }
                onMessagesChanged?.invoke()
            }
        }
        a.onStreaming = { text ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                val msg = getOrCreateStreamingAssistant()
                val idx = messages.indexOfLast { it.id == msg.id }
                if (idx >= 0) messages[idx] =
                    messages[idx].copy(content = text, version = messages[idx].version + 1)
                onMessagesChanged?.invoke()
            }
        }
        a.onThinkingDelta = { text ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                isThinking = true
                upsertThinkingMessage(text, done = false)
                onMessagesChanged?.invoke()
            }
        }
        a.onToolStreaming = { name, partial ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                val idx =
                    messages.indexOfLast { it.toolName == name && it.status == AgentMessage.MessageStatus.RUNNING }
                if (idx >= 0) messages[idx] = messages[idx].copy(
                    content = messages[idx].content + partial,
                    version = messages[idx].version + 1
                )
                onMessagesChanged?.invoke()
            }
        }
        a.onToolExecute = { name, args ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                activity = Activity.RunningTool(name)
                currentToolName = name
                val isTask = name == "task" || name == "workflow"
                messages.add(
                    AgentMessage(
                        "tool",
                        args,
                        toolName = name,
                        status = AgentMessage.MessageStatus.RUNNING,
                        isTaskAgent = isTask
                    )
                )
                onToolExecute?.invoke(name, args); onMessagesChanged?.invoke()
            }
        }
        a.onToolResult = { name, result ->
            val currentGen = generationId
            runOnEdt {
                if (generationId != currentGen) return@runOnEdt
                activity = Activity.Thinking
                currentToolName = null
                val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(
                        content = messages[idx].content + "\n---\n" + result,
                        approvalPending = false,
                        version = messages[idx].version + 1,
                        status = AgentMessage.MessageStatus.NORMAL
                    )
                } else {
                    messages.add(
                        AgentMessage(
                            "tool",
                            result,
                            toolName = name,
                            isTaskAgent = name == "task" || name == "workflow"
                        )
                    )
                }
                pendingApprovals.remove(name)
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
                isStreaming = false; isThinking = false
                streamingAssistantMsgId = -1L; streamingThinkingMsgId = -1L
                activity = Activity.Idle
                // Rate Limit 检测：若错误消息包含 429 状态码，标记限流状态并在若干秒后自动恢复
                if (msg.contains("429")) {
                    isRateLimited = true
                    onError?.invoke(msg)
                    // 60 秒后用 javax.swing.Timer 在 EDT 上自动重置限流状态
                    rateLimitTimer?.stop()
                    rateLimitTimer = javax.swing.Timer(60_000) {
                        isRateLimited = false
                        onError?.invoke(null)  // 限流到期后清除错误横幅
                    }.apply {
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
                if (streaming) { if (activity == Activity.Idle) activity = Activity.Thinking } else {
                    activity = Activity.Idle; currentToolName = null; isThinking =
                        false; autoSaveSession(); streamingAssistantMsgId =
                        -1L; streamingThinkingMsgId = -1L
                }
                onStreamingStateChanged?.invoke(streaming)
            }
        }
        a.onThinking = { text ->
            runOnEdt {
                // 仅"思考中"语义更新状态；null（清空）不再直接清状态，避免 null 间隙闪烁。
                // 不触发 onMessagesChanged，否则当前轮 streamingContent 还未清空时就 rebuild，
                // 导致同一份 AI 回复先作为流式气泡渲染一次，又被 callback 的 messages 渲染一次（重复显示）。
                // 使用 isThinking 标记而非 text.contains("思考")，避免中文语言依赖。
                // 保护 RunningTool 状态不被 onThinking 覆盖：
                // AgentLoop 会在工具执行前调用 onToolExecute(RunningTool) 然后立即 onThinking("执行...")。
                // 此时 isThinking 仍为 true（上一轮的 thinking 标记），但 UI 应显示工具 spinner 而非思考指示器。
                if (text != null && isThinking && activity !is Activity.RunningTool) {
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
            // compact 只修改 conversationHistory（API 上下文），不改 messages（UI 显示）
            // 对齐 Claude Code：对话历史压缩对用户透明
            runOnEdt {
                messages.add(AgentMessage("system", "📋 对话已自动压缩（摘要注入 system prompt，UI 历史不变）"))
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
        generationId++
        streamingAssistantMsgId = -1L; streamingThinkingMsgId = -1L  // 重置流式消息追踪

        val resolved = resolveSkillInvocation(content)
        val msg = AgentMessage("user", resolved.displayContent, images = images)
        messages.add(msg)
        if (refChips.isNotEmpty()) {
            messageRefChips[msg.id] = refChips
        }

        isStreaming = true; activity = Activity.Thinking
        runOnEdt {
            onMessagesChanged?.invoke()
            onStreamingStateChanged?.invoke(true)
        }

        val llmContent = if (refContent.isNotEmpty()) "${resolved.llmContent}\n\n$refContent" else resolved.llmContent
        val a = agent
        if (a != null) {
            if (resolved.preferredModel != null) a.switchModel(resolved.preferredModel!!)
            a.ctx.activatedSkillPrompt = resolved.skillPrompt
            val currentGen = generationId
            a.run(llmContent, apiKey, images, resolved.skillName) { finalText, thinking ->
                runOnEdt {
                    if (generationId != currentGen) return@runOnEdt
                    isStreaming = false
                    if (thinking.isNotEmpty()) upsertThinkingMessage(thinking, done = true)
                    if (finalText.isNotEmpty()) {
                        finalizeStreamingAssistant(
                            finalText,
                            a.ctx.lastInputTokens,
                            a.ctx.lastOutputTokens
                        )
                    }
                    activity = Activity.Idle
                    onMessagesChanged?.invoke()
                    onStreamingStateChanged?.invoke(false)
                }
            }
        } else {
            isStreaming = false
            runOnEdt {
                messages.add(AgentMessage("assistant", "Agent 未初始化，请重新打开对话窗口"))
                activity = Activity.Idle; onMessagesChanged?.invoke()
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
        // Stop hook
        agent?.ctx?.hookEventBus?.fire("Stop", mapOf("project_dir" to project?.basePath))
    }

    fun clearConversation() {
        stopGeneration()  // 内部已调用 rateLimitTimer?.stop()
        generationId++  // 废弃所有 pending EDT 回调，防止 clear 后追加孤立消息
        // 不在此处 join agent 线程——stopGeneration() 已设置 cancelled=true + interrupt，
        // generationId++ 确保陈旧回调被丢弃。join 会阻塞 EDT 并与 agent finally 块的
        // invokeLater 形成死锁（详见 bug-review-2026-06-19-round2 B1）。
        // 自动提取记忆（仅当对话足够长时触发，防并发）
        // 必须在 messages.clear() 之前 snapshot，否则 messages.size 永为 0
        val MIN_MESSAGES_FOR_EXTRACT = 6
        val snapshotMessages = messages.toList()
        val snapshotHistory = agent?.ctx?.conversationHistory?.toList() ?: emptyList()
        if (snapshotMessages.size >= MIN_MESSAGES_FOR_EXTRACT && extractingMemory.compareAndSet(false, true)) {
            val memEngine = agent?.ctx?.memoryEngine
            val apiKey = try { com.aiassistant.AppSettingsService.getInstance().getApiKey() } catch (_: Exception) { null }
            if (memEngine != null && apiKey != null && apiKey.isNotBlank() && com.aiassistant.AppSettingsService.isMemoryEnabled()) {
                if (snapshotHistory.isNotEmpty()) {
                    Thread({
                        try {
                            com.aiassistant.agent.memory.MemoryAutoExtract(memEngine).extract(
                                snapshotHistory.map { msg ->
                                    when (msg) {
                                        is com.aiassistant.AssistantMessage -> com.aiassistant.AssistantMessage(msg.text)
                                        is com.aiassistant.UserMessage -> com.aiassistant.UserMessage(msg.content)
                                        is com.aiassistant.ToolResultMessage -> com.aiassistant.UserMessage(
                                            "[tool_result id=${msg.toolCallId}] ${msg.content}",
                                            groupId = msg.groupId
                                        )
                                    }
                                },
                                apiKey
                            )
                        } finally {
                            extractingMemory.set(false)
                        }
                    }, "memory-auto-extract").apply { isDaemon = true }.start()
                    // 提取线程已启动，不在此处重置标志（由 finally 块处理）
                } else {
                    extractingMemory.set(false)
                }
            } else {
                extractingMemory.set(false)
            }
        }

        agent?.ctx?.let { ctx ->
            synchronized(ctx.historyLock) { ctx.conversationHistory.clear() }
            ctx.lastInputTokens = 0
            ctx.goal = null  // 清空目标
            ctx.tokenStats.perRound.clear()  // 清除 token 统计历史，防止长时间对话内存无限增长
        }
        messages.clear()
        messageRefChips.clear()  // 清理引用芯片映射，防止跨会话污染
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

        // SessionEnd hook
        val hookBus = agent?.ctx?.hookEventBus
        if (hookBus != null) {
            hookBus.fire("SessionEnd", mapOf(
                "project_dir" to project?.basePath,
                "transcript_path" to "sessions/$autoSaveSessionId"
            ))
        }

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
     * 对齐 Claude Code /compact：仅压缩 conversationHistory（API 上下文），
     * UI 消息列表不变，session 数据不变。
     */
    fun compactConversation(apiKey: String) {
        if (!isCompacting.compareAndSet(false, true)) return
        val a = agent ?: run { isCompacting.set(false); return }
        stopGeneration()
        Thread {
            try {
                val summary = a.compactHistory(15, apiKey)
                if (summary != null) {
                    runOnEdt {
                        messages.add(AgentMessage("system", "📋 对话已压缩（摘要注入 system prompt，最近 15 条保留在 API 上下文）"))
                        onMessagesChanged?.invoke()
                    }
                }
            } finally { isCompacting.set(false) }
        }.start()
    }

    // ---- 会话持久化（增量追加）----

    // 会话保存专用单线程 executor，避免每次 autoSave 创建新 Thread
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-saver").apply { isDaemon = true }
    }
    private var autoSaveSessionId: String = java.util.UUID.randomUUID().toString().take(8)
    @Volatile private var lastSavedCount: Int = 0  // EDT 读 + 后台 Thread 写，需 @Volatile

    fun autoSaveSession() {
        val path = project?.basePath ?: return
        if (messages.isEmpty()) return
        val currentSize = messages.size
        if (currentSize == lastSavedCount) return  // 无新消息，跳过
        val snapshot = messages.filter { it.role == "user" || it.role == "assistant" }.toList()
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
        val chipSnapshot = messageRefChips.toMap().mapValues { (_, chips) ->
            chips.map { com.aiassistant.session.SessionStore.ChipDTO(it.label, it.fullPath, it.startLine, it.endLine) }
        }
        saveExecutor.submit {
            val name = snapshot.firstOrNull { it.role == "user" }?.content?.take(50) ?: "空会话"
            com.aiassistant.session.SessionStore.save(path, sessionId, name, snapshot, statsDTO, chipSnapshot)
            lastSavedCount = savedCount
        }
    }

    fun loadSession(id: String) {
        val data = project?.basePath?.let { com.aiassistant.session.SessionStore.load(it, id) } ?: return
        clearConversation()
        messages.addAll(data.messages.map { dto ->
            when (dto.role) {
                "user" -> AgentMessage("user", dto.content, id = dto.id)
                "assistant" -> AgentMessage("assistant", dto.content, id = dto.id)
                "tool_call" -> AgentMessage(
                    "tool_call", dto.content,
                    toolCallId = dto.toolCallId,
                    toolName = dto.toolName,
                    toolCalls = dto.toolCalls?.map { com.aiassistant.agent.ToolCallRequest(it.id, it.name, it.arguments) },
                    id = dto.id
                )
                "tool" -> AgentMessage(
                    "tool", dto.content,
                    toolCallId = dto.toolCallId,
                    toolName = dto.toolName,
                    id = dto.id
                )
                else -> AgentMessage(dto.role, dto.content, id = dto.id)
            }
        })
        // 恢复引用芯片
        for (dto in data.messages) {
            dto.chips?.let { chips ->
                messageRefChips[dto.id] = chips.map {
                    com.aiassistant.ChatToolWindow.RefChip(it.label, it.fullPath, it.startLine, it.endLine)
                }
            }
        }
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
