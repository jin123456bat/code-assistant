package com.aiassistant.ui.chat

import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentSession
import com.aiassistant.agent.ToolCallState
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.MessageBus
import com.intellij.openapi.application.ApplicationManager
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
    private val loop = AgentLoop(project, session)

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
            SwingUtilities.invokeLater {
                onToolCallStarted?.invoke(toolUseId, toolName, params)
            }
        }
        loop.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            SwingUtilities.invokeLater {
                onToolCallStateChanged?.invoke(toolUseId, state, result, durationMs)
            }
        }
        loop.onTurnCompleted = {
            SwingUtilities.invokeLater { onTurnCompleted?.invoke() }
        }
    }

    val messages: List<ChatMessage> get() = _messages.toList()
    private val _messages = mutableListOf<ChatMessage>()

    val isRunning: Boolean get() = session.state == AgentSession.State.PROCESSING

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

        ApplicationManager.getApplication()
            .executeOnPooledThread {
                val result = loop.run(text)
                SwingUtilities.invokeLater {
                    handleResult(result)
                    onStateChanged?.invoke()
                }
            }
    }

    private fun handlePlanCommand(task: String) {
        val planExecutor = com.aiassistant.agent.PlanExecutor(session)
        ApplicationManager.getApplication()
            .executeOnPooledThread {
                val planResult =
                    loop.run("为以下任务生成详细的执行计划，每步骤包含描述、预期工具和涉及文件：$task")
                SwingUtilities.invokeLater {
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
                    onStateChanged?.invoke()
                    store.save(session)
                }
            }
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

    fun newSession() {
        store.save(session)
        session = AgentSession()
        _messages.clear()
        MessageBus.publishSessionChanged(session.id, "CREATED")
    }

    fun cancel() {
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
