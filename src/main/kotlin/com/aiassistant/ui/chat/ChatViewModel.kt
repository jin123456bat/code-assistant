package com.aiassistant.ui.chat

import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.ToolCallState
import com.aiassistant.agent.ToolCallRecord
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.MessageBus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.time.Instant
import javax.swing.SwingUtilities
import javax.swing.Timer

class ChatViewModel(
    private val project: Project,
    restoreSessionId: String? = null
) {
    private val store = SessionStore(project)
    internal var session = if (restoreSessionId != null) store.load(restoreSessionId)
        ?: AgentSession() else AgentSession()
    private var loop = AgentLoop(project, session)

    var onMessageAdded: ((ChatMessage) -> Unit)? = null
    var onStreamingToken: ((String) -> Unit)? = null
    var onReasoningContent: ((String) -> Unit)? = null
    var onToolCallStarted: ((toolUseId: String, toolName: String, params: Map<String, Any?>) -> Unit)? =
        null
    var onToolCallStateChanged: ((toolUseId: String, state: ToolCallState, result: String?, durationMs: Long?) -> Unit)? =
        null
    var onStateChanged: (() -> Unit)? = null
    var onTurnCompleted: (() -> Unit)? = null

    // ── 30ms 批量 flush：Timer 在 EDT 上合并连续 token ──
    private val streamingBuf = StringBuilder()
    private var flushTimer: Timer? = null
    private val FLUSH_INTERVAL_MS = 30

    val messages: List<ChatMessage> get() = _messages.toList()
    private val _messages = mutableListOf<ChatMessage>()
    private var turnInFlight = false
    private var lastAgentText: String? = null
    private var lastCompletion: ((AgentLoop.Result) -> Unit)? = null

    private fun startFlushTimer() {
        if (flushTimer != null) return
        flushTimer = Timer(FLUSH_INTERVAL_MS) {
            if (streamingBuf.isNotEmpty()) {
                val batch = streamingBuf.toString()
                streamingBuf.clear()
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
    }

    private fun bindLoopCallbacks() {
        loop.onToken = { token ->
            streamingBuf.append(token)
            // 每个 token 到达时调度 flush timer（首 token 即时，后续 30ms 批量）
            if (flushTimer == null) {
                SwingUtilities.invokeLater {
                    val t = streamingBuf.toString()
                    streamingBuf.clear()
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
        loop.onTurnCompleted = {
            SwingUtilities.invokeLater { onTurnCompleted?.invoke() }
        }
    }

    private fun restoreMessages() {
        _messages.clear()
        session.messages.forEach { msg ->
            val type = when (msg.role) {
                com.aiassistant.agent.Role.USER -> ChatMessage.Type.USER_TEXT
                com.aiassistant.agent.Role.ERROR -> ChatMessage.Type.ERROR
                com.aiassistant.agent.Role.SYSTEM -> ChatMessage.Type.SYSTEM
                com.aiassistant.agent.Role.TOOL_CALL,
                com.aiassistant.agent.Role.TOOL_RESULT -> ChatMessage.Type.TOOL_CALL

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
                            result = it.result,
                            durationMs = it.durationMs
                        )
                    },
                    tokenDelta = msg.tokenUsage?.let {
                        ChatMessage.TokenDelta(it.inputTokens, it.outputTokens)
                    }
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
            role = com.aiassistant.agent.Role.TOOL_CALL,
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
            newSession(); return
        }
        if (text.trimStart().startsWith("/plan ")) {
            handlePlanCommand(text.trimStart().removePrefix("/plan ").trim())
            return
        }

        runAgentText(buildAgentText(text))
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
                            plan.steps.mapIndexed { i, s -> "Step ${i + 1}: ${s.description}\n  工具: ${s.tool}" }
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
        onComplete: (AgentLoop.Result) -> Unit = ::handleResult
    ) {
        lastAgentText = agentText
        lastCompletion = onComplete
        markTurnInFlight()
        ApplicationManager.getApplication()
            .executeOnPooledThread {
                val result = loop.run(agentText)
                SwingUtilities.invokeLater {
                    turnInFlight = false
                    onComplete(result)
                    onStateChanged?.invoke()
                }
            }
    }

    fun retryLastTurn(): Boolean {
        val agentText = lastAgentText ?: return false
        runAgentText(agentText, lastCompletion ?: ::handleResult)
        return true
    }

    private fun handleResult(result: AgentLoop.Result) {
        when (result) {
            is AgentLoop.Result.Success -> {
                // 清空 buffer 残留
                if (streamingBuf.isNotEmpty()) {
                    onStreamingToken?.invoke(streamingBuf.toString())
                    streamingBuf.clear()
                }
                // 思考过程如果有内容，通过系统消息展示
                if (!result.reasoning.isNullOrBlank()) {
                    val thinkMsg = ChatMessage(
                        type = ChatMessage.Type.SYSTEM,
                        content = result.reasoning,
                        timestamp = Instant.now()
                    )
                    _messages.add(thinkMsg)
                    onMessageAdded?.invoke(thinkMsg)
                }
                session.addMessage(
                    com.aiassistant.agent.Message(
                        role = com.aiassistant.agent.Role.ASSISTANT,
                        content = result.text.take(2000)
                    )
                )
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

    fun newSession() {
        store.save(session)
        turnInFlight = false
        lastAgentText = null
        lastCompletion = null
        session = AgentSession()
        loop = AgentLoop(project, session)
        bindLoopCallbacks()
        _messages.clear()
        MessageBus.publishSessionChanged(session.id, "CREATED")
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
    val tokenDelta: TokenDelta? = null
) {
    enum class Type { USER_TEXT, AGENT_TEXT, TOOL_CALL, ERROR, SYSTEM }
    data class TokenDelta(val input: Long, val output: Long)
}

data class ToolCallUIData(
    val toolUseId: String,
    val toolName: String,
    val state: String,
    val result: String? = null,
    val durationMs: Long? = null
)
