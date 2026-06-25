package com.aiassistant.ui.chat

import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentSession
import com.aiassistant.session.SessionStore
import com.intellij.openapi.project.Project
import java.time.Instant
import javax.swing.SwingUtilities

// ponytail: 聊天 UI 状态管理，连接 AgentLoop 和 ChatPage

class ChatViewModel(
    private val project: Project,
    restoreSessionId: String? = null
) {
    private val store = SessionStore(project)
    internal var session = if (restoreSessionId != null) store.load(restoreSessionId)
        ?: AgentSession() else AgentSession()
    private val loop = AgentLoop(project, session)
    var onMessageAdded: ((ChatMessage) -> Unit)? = null
    var onTokenAppended: ((String) -> Unit)? = null
    var onStreamingToken: ((String) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    private val streamingBuf = StringBuilder()

    init {
        loop.onToken = { token ->
            streamingBuf.append(token)
            SwingUtilities.invokeLater { onStreamingToken?.invoke(token) }
        }
    }

    val messages: List<ChatMessage> get() = _messages.toList()
    private val _messages = mutableListOf<ChatMessage>()

    val isRunning: Boolean get() = session.state == AgentSession.State.PROCESSING

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

        // Detect /plan command → generate plan first
        if (text.trimStart() == "/clear") {
            newSession(); return
        }
        if (text.trimStart().startsWith("/plan ")) {
            handlePlanCommand(text.trimStart().removePrefix("/plan ").trim())
            return
        }

        // Normal agent execution
        com.intellij.openapi.application.ApplicationManager.getApplication()
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
        com.intellij.openapi.application.ApplicationManager.getApplication()
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
            }

            is AgentLoop.Result.Error -> {
                val errMsg = ChatMessage(
                    type = ChatMessage.Type.ERROR,
                    content = "错误: ${result.message}",
                    timestamp = Instant.now()
                )
                _messages.add(errMsg); onMessageAdded?.invoke(errMsg)
            }
        }
        store.save(session)
    }

    fun newSession() {
        store.save(session) // save current before switching
        session = AgentSession()
        _messages.clear()
    }

    fun cancel() {
        session.cancel()
        session.runningProcesses.forEach { if (it.isAlive) it.destroyForcibly() }
        session.runningProcesses.clear()
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
    val toolName: String,
    val state: String,
    val result: String? = null,
    val durationMs: Long? = null
)
