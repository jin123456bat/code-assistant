package com.aiassistant.ui.chat

import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.FileRef
import com.aiassistant.agent.ImageRef
import com.aiassistant.agent.MultiAgentManager
import com.aiassistant.agent.Role
import com.aiassistant.agent.TokenEstimator
import com.aiassistant.agent.TokenUsage
import com.aiassistant.agent.ToolApprovalRequest
import com.aiassistant.agent.ToolCallState
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.session.SessionManager
import com.aiassistant.session.SessionStore
import com.aiassistant.skills.SkillManager
import com.aiassistant.ui.MessageBus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.time.Instant
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 简易 ObservableString，支持监听器，用于 UI 数据绑定。
 * 当字符串值变化时，通过 [onChanged] 回调通知 UI 层。
 * 对齐 docs/ui/chat.md §十二 ChatViewModel.streamingToken 和 docs/ui/components.md §四。
 */
class ObservableString(initialValue: String = "") {
    /** 当前值 */
    var value: String = initialValue
        private set

    /** 值变化监听器，UI 层通过此回调进行数据绑定 */
    var onChanged: ((String) -> Unit)? = null

    /**
     * 设置新值并通知监听器。
     * 若新值与当前值相同则不触发通知。
     */
    fun set(newValue: String) {
        value = newValue
        onChanged?.invoke(newValue)
    }
}

/**
 * 简易 ObservableList，支持添加/移除监听器，用于 UI 数据绑定。
 * 当列表内容变化时，通过 [onChanged] 回调通知 UI 层刷新。
 */
class ObservableList<T> private constructor(
    private val delegate: MutableList<T>
) : MutableList<T> by delegate {
    /** 列表变化监听器，UI 层通过此回调进行数据绑定驱动更新 */
    var onChanged: (() -> Unit)? = null

    companion object {
        fun <T> create(): ObservableList<T> = ObservableList(mutableListOf())
    }

    override fun add(element: T): Boolean {
        val result = delegate.add(element)
        if (result) onChanged?.invoke()
        return result
    }

    override fun add(index: Int, element: T) {
        delegate.add(index, element)
        onChanged?.invoke()
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val result = delegate.addAll(elements)
        if (result) onChanged?.invoke()
        return result
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        val result = delegate.addAll(index, elements)
        if (result) onChanged?.invoke()
        return result
    }

    override fun clear() {
        if (delegate.isNotEmpty()) {
            delegate.clear()
            onChanged?.invoke()
        }
    }

    override fun remove(element: T): Boolean {
        val result = delegate.remove(element)
        if (result) onChanged?.invoke()
        return result
    }

    override fun removeAt(index: Int): T {
        val result = delegate.removeAt(index)
        onChanged?.invoke()
        return result
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        val result = delegate.removeAll(elements)
        if (result) onChanged?.invoke()
        return result
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        val result = delegate.retainAll(elements)
        if (result) onChanged?.invoke()
        return result
    }

    override fun set(index: Int, element: T): T {
        val old = delegate.set(index, element)
        onChanged?.invoke()
        return old
    }
}

class ChatViewModel(
    private val project: Project,
    restoreSessionId: String? = null
) {
    private val store = SessionStore(project)
    private val sessionManager = SessionManager(project)
    private val skillManager = SkillManager(project)
    internal var session = if (restoreSessionId != null) store.load(restoreSessionId)
        ?: AgentSession() else AgentSession()
    private var loop = AgentLoop(project, session)

    var onMessageAdded: ((ChatMessage) -> Unit)? = null

    /** 当前流式 token（UI 绑定），对齐 docs/ui/chat.md §十二 和 docs/ui/components.md §四 */
    val streamingToken = ObservableString()

    @Deprecated(
        "请使用 streamingToken ObservableString 属性进行 UI 绑定",
        ReplaceWith("streamingToken.onChanged = { ... }")
    )
    var onStreamingToken: ((String) -> Unit)? = null
    var onReasoningContent: ((String) -> Unit)? = null
    var onToolCallStarted: ((toolUseId: String, toolName: String, params: Map<String, Any?>) -> Unit)? =
        null
    var onToolCallStateChanged: ((toolUseId: String, state: ToolCallState, result: String?, durationMs: Long?) -> Unit)? =
        null
    var onApprovalRequested: ((ToolApprovalRequest) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null
    var onTurnCompleted: (() -> Unit)? = null
    var onSubAgentEvent: ((MultiAgentManager.SubAgentEvent) -> Unit)? = null

    /** 会话标题异步生成后回调，对齐 docs/ui/pages.md §十二 ChatPage 标题行 */
    var onTitleChanged: ((String) -> Unit)? = null

    // ── 30ms 批量 flush：Timer 在 EDT 上合并连续 token ──
    private val streamingBuf = StringBuilder()
    private var flushTimer: Timer? = null
    private val FLUSH_INTERVAL_MS = 30

    /** UI 绑定列表（只读），对齐 docs/ui/chat.md §十二 messages: ObservableList<ChatMessage>（只读） */
    val messages: List<ChatMessage> get() = _messages
    private val _messages = ObservableList.create<ChatMessage>()
    private var turnInFlight = false
    private var lastAgentText: String? = null
    private var lastSlashCommand: String? = null
    private var lastCompletion: ((AgentLoop.Result) -> Unit)? = null

    private fun startFlushTimer() {
        if (flushTimer != null) return
        flushTimer = Timer(FLUSH_INTERVAL_MS) {
            if (streamingBuf.isNotEmpty()) {
                val batch = streamingBuf.toString()
                streamingBuf.clear()
                streamingToken.set(batch)
                onStreamingToken?.invoke(batch)
            }
        }.apply { isRepeats = false; start() }
    }

    private fun scheduleFlush() {
        flushTimer?.restart()
        if (flushTimer == null) startFlushTimer()
    }

    init {
        restoreMessages()
        bindLoopCallbacks()
        // 订阅会话标题异步生成回调，对齐 docs/ui/pages.md §十二 ChatPage 标题行
        sessionManager.onTitleGenerated = { sessionId, title ->
            if (sessionId == session.id) {
                SwingUtilities.invokeLater { onTitleChanged?.invoke(title) }
            }
        }
    }

    private fun bindLoopCallbacks() {
        loop.onToken = { token ->
            streamingBuf.append(token)
            // 每个 token 到达时调度 flush timer（首 token 即时，后续 30ms 批量）
            if (flushTimer == null) {
                SwingUtilities.invokeLater {
                    val t = streamingBuf.toString()
                    streamingBuf.clear()
                    streamingToken.set(t)
                    onStreamingToken?.invoke(t)
                }
            }
            scheduleFlush()
        }
        loop.onReasoningContent = { reasoning ->
            SwingUtilities.invokeLater { onReasoningContent?.invoke(reasoning) }
        }
        loop.onToolCall = { toolUseId, toolName, params ->
            recordToolCallStarted(toolUseId, toolName, params)
            SwingUtilities.invokeLater {
                onToolCallStarted?.invoke(toolUseId, toolName, params)
            }
        }
        loop.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            recordToolCallState(toolUseId, state, result, durationMs)
            SwingUtilities.invokeLater {
                onToolCallStateChanged?.invoke(toolUseId, state, result, durationMs)
                onStateChanged?.invoke()
            }
        }
        loop.onApprovalRequested = { request ->
            SwingUtilities.invokeLater {
                onApprovalRequested?.invoke(request)
            }
        }
        loop.onTurnCompleted = {
            SwingUtilities.invokeLater { onTurnCompleted?.invoke() }
        }
        loop.onSubAgentEvent = { event ->
            SwingUtilities.invokeLater { onSubAgentEvent?.invoke(event) }
        }
    }

    private fun restoreMessages() {
        _messages.clear()
        session.messages.forEach { msg ->
            val type = when {
                msg.role == com.aiassistant.agent.Role.USER -> ChatMessage.Type.USER_TEXT
                msg.role == com.aiassistant.agent.Role.ERROR -> ChatMessage.Type.ERROR
                msg.role == com.aiassistant.agent.Role.SYSTEM && msg.contentType == null -> ChatMessage.Type.SYSTEM
                msg.contentType == com.aiassistant.agent.ContentType.TOOL_USE
                        || msg.contentType == com.aiassistant.agent.ContentType.TOOL_RESULT -> ChatMessage.Type.TOOL_CALL
                else -> ChatMessage.Type.AGENT_TEXT
            }
            val toolCall = msg.toolCalls?.firstOrNull()
            val content =
                if (type == ChatMessage.Type.TOOL_CALL && msg.content.isBlank() && toolCall != null) {
                    formatToolParams(toolCall.parameters)
                } else {
                    msg.content
                }
            _messages.add(
                ChatMessage(
                    id = msg.id,
                    type = type,
                    content = content,
                    timestamp = msg.timestamp,
                    toolCall = toolCall?.let {
                        ToolCallUIData(
                            toolUseId = it.id,
                            toolName = it.name,
                            state = it.state.name,
                            parameters = it.parameters,
                            result = it.result,
                            durationMs = it.durationMs
                        )
                    },
                    tokenDelta = msg.tokenUsage?.let {
                        ChatMessage.TokenDelta(it.inputTokens, it.outputTokens)
                    },
                    feedback = msg.feedback
                )
            )
        }
    }

    private fun recordToolCallStarted(
        toolUseId: String,
        toolName: String,
        params: Map<String, Any?>
    ) {
        val content = formatToolParams(params)
        val record = ToolCallRecord(
            id = toolUseId,
            name = toolName,
            parameters = params,
            state = ToolCallState.PENDING
        )
        val existingIndex = session.messages.indexOfLast {
            it.toolCalls?.any { call -> call.id == toolUseId } == true
        }
        val message = com.aiassistant.agent.Message(
            role = com.aiassistant.agent.Role.SYSTEM,
            contentType = com.aiassistant.agent.ContentType.TOOL_USE,
            content = content,
            toolCalls = listOf(record)
        )
        if (existingIndex >= 0) session.messages[existingIndex] = message else session.addMessage(
            message
        )
    }

    private fun recordToolCallState(
        toolUseId: String,
        state: ToolCallState,
        result: String?,
        durationMs: Long?
    ) {
        val index = session.messages.indexOfLast {
            it.toolCalls?.any { call -> call.id == toolUseId } == true
        }
        if (index < 0) return
        val current = session.messages[index]
        val updatedCalls = current.toolCalls?.map { call ->
            if (call.id == toolUseId) call.copy(
                state = state,
                result = result,
                durationMs = durationMs
            ) else call
        }
        session.messages[index] = current.copy(toolCalls = updatedCalls)
    }

    private fun formatToolParams(params: Map<String, Any?>): String =
        params.entries.joinToString(", ") { "${it.key}=${it.value}" }

    val isRunning: Boolean
        get() = turnInFlight || session.state in setOf(
            AgentSession.State.PROCESSING,
            AgentSession.State.EXECUTING,
            AgentSession.State.AWAITING_APPROVAL
        )
    val canRetry: Boolean get() = lastAgentText != null

    // ── 暴露 session id 和 plan 供 ChatPage 使用 ──
    val sessionId: String get() = session.id
    val currentPlan: com.aiassistant.agent.PlanExecutor.Plan? get() = session.plan

    fun removePlanStep(stepId: String): Boolean {
        val plan = session.plan ?: return false
        val step = plan.plans.find { it.id == stepId } ?: return false
        if (step.status != com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.PAUSED) return false
        step.status = com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.CANCELLED
        if (plan.currentPlanIndex < plan.plans.size && plan.plans[plan.currentPlanIndex].id == stepId) {
            plan.currentPlanIndex++
        }
        if (plan.plans.all {
                it.status == com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.COMPLETED ||
                        it.status == com.aiassistant.agent.PlanExecutor.PlanItem.ItemStatus.CANCELLED
            }) {
            plan.status = com.aiassistant.agent.PlanExecutor.Plan.Status.COMPLETED
        }
        plan.updatedAt = Instant.now()
        store.save(session)
        MessageBus.publishPlanStateChanged(session.id, plan.status.name)
        onStateChanged?.invoke()
        return true
    }

    // ── 输入区域状态（对齐 docs/ui/chat.md §十二）──
    /** 输入区域状态：文件引用、图片、token 估算，供 UI 绑定 */
    var inputState = InputState()
        private set


    /**
     * 更新输入文本的估算 token 数（不含图片），对齐 docs/ui/chat.md §十二 InputState.tokenCount。
     * 输入文本变化时由 ChatInputArea 回调，ChatViewModel 统一维护 InputState。
     *
     * @param text 当前输入框文本
     * @param manualRefs @file 手动引用列表
     * @param selectionRef 选中代码引用
     * @param images 图片引用列表
     */
    fun updateInputState(
        text: String = "",
        manualRefs: List<FileRef> = inputState.manualRefs,
        selectionRef: FileRef? = inputState.selectionRef,
        images: List<ImageRef> = inputState.images
    ) {
        // 估算输入文本 token 数（文本内容 + @file 引用注入内容 + 选中代码内容），不含图片
        val textTokens = if (text.isBlank()) 0 else TokenEstimator.estimateTokens(text)
        // @file 引用的文件内容 token 估算
        val fileRefTokens = manualRefs.filter { it.content != null }.sumOf {
            TokenEstimator.estimateTokens(it.content ?: "")
        }
        // 选中代码内容 token 估算
        val selectionTokens = selectionRef?.content?.let { TokenEstimator.estimateTokens(it) } ?: 0
        val totalTokenCount = textTokens + fileRefTokens + selectionTokens

        inputState = InputState(
            manualRefs = manualRefs,
            selectionRef = selectionRef,
            images = images,
            tokenCount = totalTokenCount
        )
    }

    /**
     * 添加文件引用（手动 @file），对齐 docs/ui/chat.md §十二 addFileRef()。
     * 委托 updateInputState 统一更新 InputState。
     */
    fun addFileRef(ref: FileRef) {
        val updated = inputState.manualRefs.toMutableList()
        if (updated.none { it.path == ref.path }) {
            updated.add(ref)
        }
        updateInputState(manualRefs = updated)
    }

    /**
     * 移除文件引用，对齐 docs/ui/chat.md §十二 removeFileRef()。
     * 委托 updateInputState 统一更新 InputState。
     */
    fun removeFileRef(ref: FileRef) {
        val updated = inputState.manualRefs.filter { it.path != ref.path }
        updateInputState(manualRefs = updated)
    }

    /**
     * 更新选中代码引用（仅一个，新选中替换旧引用），对齐 docs/ui/chat.md §十二 updateSelectionRef()。
     * 委托 updateInputState 统一更新 InputState。
     */
    fun updateSelectionRef(file: String, lines: IntRange?, content: String) {
        val ref = FileRef(
            path = file,
            lines = if (lines != null) "$lines" else null,
            content = content
        )
        updateInputState(selectionRef = ref)
    }

    /**
     * 取消选中时清除选中引用，对齐 docs/ui/chat.md §十二 clearSelectionRef()。
     * 委托 updateInputState 统一更新 InputState。
     */
    fun clearSelectionRef() {
        updateInputState(selectionRef = null)
    }

    /**
     * 添加粘贴图片引用，对齐 docs/ui/chat.md §十二 addImage()。
     * 单次最多 5 张，委托 updateInputState 统一更新 InputState。
     */
    fun addImage(image: ImageRef) {
        val updated = inputState.images.toMutableList()
        if (updated.size < 5) {
            updated.add(image)
        }
        updateInputState(images = updated)
    }

    /**
     * 移除图片引用，对齐 docs/ui/chat.md §十二 removeImage()。
     * 委托 updateInputState 统一更新 InputState。
     */
    fun removeImage(imageId: String) {
        val updated = inputState.images.filter { it.id != imageId }
        updateInputState(images = updated)
    }

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            type = ChatMessage.Type.USER_TEXT, content = text, timestamp = Instant.now()
        )
        _messages.add(userMsg); onMessageAdded?.invoke(userMsg)
        session.addMessage(
            com.aiassistant.agent.Message(
                role = com.aiassistant.agent.Role.USER,
                content = text
            )
        )

        if (text.trimStart() == "/clear") {
            clearSession(); return
        }
        if (text.trimStart().startsWith("/plan ")) {
            handlePlanCommand(text.trimStart().removePrefix("/plan ").trim())
            return
        }

        // 新会话第一条用户消息：异步生成标题（对齐 docs/ui/pages.md §二）
        val userMessagesCount = session.messages.count { it.role == Role.USER }
        if (userMessagesCount == 1 && session.title == "新会话") {
            store.save(session)
            sessionManager.generateTitle(session.id)
        }

        runAgentText(buildAgentText(text), slashCommand = resolveSlashCommand(text))
    }

    internal fun resolveSlashCommandForTest(text: String): String? = resolveSlashCommand(text)

    private fun resolveSlashCommand(text: String): String? {
        val firstToken = text.trimStart().substringBefore(' ')
        if (!firstToken.startsWith("/") || firstToken in setOf("/clear", "/plan")) return null
        return firstToken.takeIf { it in skillManager.enabledSlashCommands() }
    }

    private fun handlePlanCommand(task: String) {
        val planExecutor = com.aiassistant.agent.PlanExecutor(session)
        val agentTask = buildAgentText(task)
        runAgentText("为以下任务生成详细的执行计划，每步骤包含描述、预期工具和涉及文件：$agentTask") { planResult ->
            when (planResult) {
                is AgentLoop.Result.Success -> {
                    val plan = planExecutor.parsePlan(planResult.text)
                    planExecutor.currentPlan = plan
                    session.plan = plan
                    val planMsg = ChatMessage(
                        type = ChatMessage.Type.AGENT_TEXT,
                        content = "📋 执行计划已生成\n\n${
                            plan.plans.mapIndexed { i, s -> "Step ${i + 1}: ${s.description}\n  工具: ${s.tool}" }
                                .joinToString("\n\n")
                        }",
                        timestamp = Instant.now()
                    )
                    _messages.add(planMsg); onMessageAdded?.invoke(planMsg)
                    MessageBus.publishPlanStateChanged(session.id, plan.status.name)
                }

                is AgentLoop.Result.Error -> {
                    val errMsg = ChatMessage(
                        type = ChatMessage.Type.ERROR,
                        content = "计划生成失败: ${planResult.message}",
                        timestamp = Instant.now()
                    )
                    _messages.add(errMsg); onMessageAdded?.invoke(errMsg)
                }
            }
            store.save(session)
        }
    }

    private fun markTurnInFlight() {
        turnInFlight = true
        onStateChanged?.invoke()
    }

    private fun runAgentText(
        agentText: String,
        slashCommand: String? = null,
        onComplete: (AgentLoop.Result) -> Unit = ::handleResult
    ) {
        lastAgentText = agentText
        lastSlashCommand = slashCommand
        lastCompletion = onComplete
        markTurnInFlight()
        ApplicationManager.getApplication()
            .executeOnPooledThread {
                val result = loop.run(agentText, slashCommand = slashCommand)
                SwingUtilities.invokeLater {
                    turnInFlight = false
                    onComplete(result)
                    onStateChanged?.invoke()
                }
            }
    }

    fun retryLastTurn(): Boolean {
        val agentText = lastAgentText ?: return false
        runAgentText(agentText, lastSlashCommand, lastCompletion ?: ::handleResult)
        return true
    }

    private fun handleResult(result: AgentLoop.Result) {
        when (result) {
            is AgentLoop.Result.Success -> {
                // 清空 buffer 残留
                if (streamingBuf.isNotEmpty()) {
                    streamingToken.set(streamingBuf.toString())
                    onStreamingToken?.invoke(streamingBuf.toString())
                    streamingBuf.clear()
                }
                // 思考内容不持久化到 Session JSON（对齐 docs/ui/chat.md §三），
                // reasoning 已在流式阶段通过 onReasoningContent 实时渲染，这里无需额外处理
                // stop_sequence 已在 AgentLoop 内持久化 assistant 消息（含标注），避免重复写入
                if (!result.alreadyPersisted) {
                    session.addMessage(
                        com.aiassistant.agent.Message(
                            role = com.aiassistant.agent.Role.ASSISTANT,
                            content = result.text.take(2000)
                        )
                    )
                }
                val agentMsg = ChatMessage(
                    type = ChatMessage.Type.AGENT_TEXT, content = result.text,
                    timestamp = Instant.now(),
                    tokenDelta = ChatMessage.TokenDelta(
                        input = result.turns * 1000L,
                        output = result.text.length / 4L
                    )
                )
                _messages.add(agentMsg); onMessageAdded?.invoke(agentMsg)
                MessageBus.publishTokenUsageUpdated(session.id, result.text.length / 4L)
            }

            is AgentLoop.Result.Error -> {
                if (streamingBuf.isNotEmpty()) {
                    streamingToken.set(streamingBuf.toString())
                    onStreamingToken?.invoke(streamingBuf.toString())
                    streamingBuf.clear()
                }
                val errMsg = ChatMessage(
                    type = ChatMessage.Type.ERROR,
                    content = result.message,
                    timestamp = Instant.now()
                )
                _messages.add(errMsg); onMessageAdded?.invoke(errMsg)
            }
        }
        store.save(session)
        MessageBus.publishAgentStateChanged(session.id, session.state.name)
    }

    private fun buildAgentText(text: String): String {
        val withFiles = FileReferenceResolver.expand(text, project.basePath)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return withFiles
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return withFiles
        val fileName = FileDocumentManager.getInstance().getFile(editor.document)?.presentableName
        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
        val displayName = if (fileName != null) "$fileName:$startLine-$endLine" else null
        return SelectionReferenceResolver.expand(withFiles, displayName, selection.selectedText)
    }

    /**
     * 清空当前会话内容，保留 session.id 和 approvedTools。
     * 对齐 docs/ui/components.md §4 clearSession()：
     *   - session.messages.clear()
     *   - session.compactSummary = null, session.compactCount = 0
     *   - session.plan = null
     *   - session.totalTokens 归零
     *   - session.approvedTools 保留（不清除审批信任）
     *   - 复用当前 session.id，不新建文件
     */
    fun clearSession() {
        store.save(session)

        // 清除会话内容（复用 session.id，对齐 docs/ui/components.md §4）
        // 显式保留 approvedTools：clearSession() 复用同一 session 对象，approvedTools 自然保留。
        // 此处显式声明意图以对齐文档要求：session.approvedTools 保留（不清除审批信任）。
        val preservedApprovedTools = session.approvedTools.toMutableSet()
        session.messages.clear()
        session.approvedTools.addAll(preservedApprovedTools)
        session.compactSummary = null
        session.compactCount = 0
        session.plan = null
        session.totalTokens = TokenUsage()
        session.errorCount = 0
        session.filesReadThisTurn.clear()

        turnInFlight = false
        lastAgentText = null
        lastSlashCommand = null
        lastCompletion = null
        loop = AgentLoop(project, session)
        bindLoopCallbacks()
        _messages.clear()
        MessageBus.publishSessionChanged(session.id, "CLEARED")
    }

    fun newSession() {
        store.save(session)
        val approvedTools = session.approvedTools.toMutableSet()
        turnInFlight = false
        lastAgentText = null
        lastSlashCommand = null
        lastCompletion = null
        session = AgentSession()
        // 保留旧 session 的 approvedTools，不清除审批信任（对齐 docs/ui/components.md §4 clearSession()）
        session.approvedTools.addAll(approvedTools)
        // 显式归零 totalTokens（对齐 docs/ui/chat.md §十二 clearSession()）
        session.totalTokens = TokenUsage()
        loop = AgentLoop(project, session)
        bindLoopCallbacks()
        _messages.clear()
        MessageBus.publishSessionChanged(session.id, "CREATED")
    }

    /**
     * 切换到已有会话（对齐 docs/ui/pages.md §二：CardLayout 不销毁隐藏页面，
     * ChatPage 复用同一实例，仅切换内部 session）。
     */
    fun restoreSession(sessionId: String?) {
        store.save(session)
        turnInFlight = false
        lastAgentText = null
        lastSlashCommand = null
        lastCompletion = null
        session = if (sessionId != null) store.load(sessionId) ?: AgentSession() else AgentSession()
        loop = AgentLoop(project, session)
        bindLoopCallbacks()
        _messages.clear()
        restoreMessages()
        MessageBus.publishSessionChanged(session.id, "RESTORED")
    }

    /**
     * 记录用户对 assistant 消息的反馈（对齐 docs/ui/chat.md §十）。
     * 将 feedback 写入 Session Message 并持久化。
     */
    fun recordFeedback(messageId: String, feedback: String) {
        // 更新 Session 中的 Message
        val sessionMsgIndex = session.messages.indexOfLast { it.id == messageId }
        if (sessionMsgIndex >= 0) {
            val msg = session.messages[sessionMsgIndex]
            session.messages[sessionMsgIndex] = msg.copy(feedback = feedback)
            store.save(session)
        }
        // 更新内存中的 ChatMessage 列表
        val chatMsgIndex = _messages.indexOfFirst { it.id == messageId }
        if (chatMsgIndex >= 0) {
            val msg = _messages[chatMsgIndex]
            _messages[chatMsgIndex] = msg.copy(feedback = feedback)
            onStateChanged?.invoke()
        }
    }

    // ── 对话回退功能（对齐 docs/ui/chat.md §十二）──

    /**
     * 是否存在可撤销的回退（对齐 docs/ui/chat.md §十二）。
     * 当 _messages 中存在被标记为 deleted 的消息时返回 true。
     */
    val hasPendingRollback: Boolean
        get() = _messages.any { it.deleted }

    /**
     * 回退到指定消息：将 messageId 之后的所有消息标记为 deleted=true（对齐 docs/ui/chat.md §十二）。
     * 委托 AgentSession.rollbackTo 标记 Session 消息，同步更新 UI 层 _messages，并持久化。
     */
    fun rollbackToMessage(messageId: String) {
        val targetIndex = _messages.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) return

        // 委托 AgentSession 标记 session.messages 中 messageId 之后的消息 deleted=true
        session.rollbackTo(messageId)

        // 同步更新 UI 层 _messages 的 deleted 标记
        for (i in (targetIndex + 1)..<_messages.size) {
            val msg = _messages[i]
            if (!msg.deleted) {
                _messages[i] = msg.copy(deleted = true)
            }
        }

        store.save(session)
        onStateChanged?.invoke()
    }

    /**
     * 撤销回退：将所有被标记为 deleted 的消息恢复为 deleted=false（对齐 docs/ui/chat.md §十二）。
     */
    fun undoRollback() {
        // 恢复 session.messages 中的所有 deleted 标记
        session.messages.forEach { it.deleted = false }

        // 恢复 UI 层 _messages 中的所有 deleted 标记
        for (i in _messages.indices) {
            val msg = _messages[i]
            if (msg.deleted) {
                _messages[i] = msg.copy(deleted = false)
            }
        }

        store.save(session)
        onStateChanged?.invoke()
    }

    fun cancel() {
        turnInFlight = false
        session.cancel()
        session.runningProcesses.forEach { if (it.isAlive) it.destroyForcibly() }
        session.runningProcesses.clear()
        flushTimer?.stop()
        flushTimer = null
        streamingBuf.clear()
    }
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: Type,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val toolCall: ToolCallUIData? = null,
    val tokenDelta: TokenDelta? = null,
    /** 用户反馈: "positive" | "negative"，仅 AGENT_TEXT 消息有此字段（对齐 docs/ui/chat.md §十） */
    val feedback: String? = null,
    /** 回退标记：true 表示该消息已被回退（对齐 docs/ui/chat.md §十二 rollbackToMessage） */
    val deleted: Boolean = false
) {
    /** TOOL_RESULT 不独立渲染，结果内嵌在 ToolCallCard 中 */
    enum class Type { USER_TEXT, AGENT_TEXT, TOOL_CALL, ERROR, SYSTEM }
    data class TokenDelta(val input: Long, val output: Long)
}

data class ToolCallUIData(
    val toolUseId: String,
    val toolName: String,
    val state: String,
    /** 工具参数，Edit 工具包含 oldString/newString 用于 Diff 可视化 */
    val parameters: Map<String, Any?> = emptyMap(),
    val result: String? = null,
    val durationMs: Long? = null
)

/**
 * 输入区域状态，对齐 docs/ui/chat.md §十二 ChatViewModel 接口。
 * 包含文件引用、选中代码引用、图片引用和 token 估算数。
 */
data class InputState(
    /** @file 手动引用（可多个），对齐 docs/ui/chat.md §十二 InputState.manualRefs */
    val manualRefs: List<FileRef> = emptyList(),
    /** 选中代码引用（仅一个），对齐 docs/ui/chat.md §十二 InputState.selectionRef */
    val selectionRef: FileRef? = null,
    /** 粘贴的图片（可多个，单次 ≤ 5 张），对齐 docs/ui/chat.md §十二 InputState.images */
    val images: List<ImageRef> = emptyList(),
    /** 当前输入文本估算 token 数（不含图片），对齐 docs/ui/chat.md §十二 InputState.tokenCount */
    val tokenCount: Int = 0
)
