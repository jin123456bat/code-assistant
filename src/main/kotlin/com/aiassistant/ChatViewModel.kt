package com.aiassistant

import com.aiassistant.agent_v3.AgentContext
import com.aiassistant.agent_v3.AgentLoop
import com.aiassistant.agent_v3.AgentMessage
import com.aiassistant.mcp.McpManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

/**
 * v3 UI 桥接 — 轻量 ViewModel，委托给 AgentLoop。
 */
class ChatViewModel(
    private val sseClient: SseClient = SseClient(),
    private val anthropicAdapter: AnthropicAdapter = AnthropicAdapter()
) {
    @Volatile var messages = mutableListOf<AgentMessage>()
    @Volatile var streamingContent = ""
    @Volatile var isStreaming = false
    @Volatile var isRateLimited = false
    @Volatile var currentToolName: String? = null
    @Volatile var currentPlan: AgentContext.Plan? = null
    @Volatile var currentModel: String = "deepseek-chat"

    var onMessagesChanged: (() -> Unit)? = null
    var onStreamingUpdate: ((String) -> Unit)? = null
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String?) -> Unit)? = null
    var onRateLimitCountdown: ((Int) -> Unit)? = null
    var onToolExecute: ((String, String) -> Unit)? = null
    var onToolResult: ((String, String) -> Unit)? = null
    var onPlanUpdate: ((AgentContext.Plan) -> Unit)? = null
    var onModelRouted: ((String) -> Unit)? = null
    var onConfirmTool: ((String, String, CountDownLatch, AtomicBoolean) -> Unit)? = null
    var onThinkingContent: ((String) -> Unit)? = null

    private var agent: AgentLoop? = null
    private var project: Project? = null

    fun initialize(project: Project, mcpTools: List<com.aiassistant.agent.AgentTool> = emptyList()) {
        this.project = project
        val a = AgentLoop(project, sseClient, anthropicAdapter)
        a.initialize(mcpTools)
        setupCallbacks(a)
        agent = a
    }

    fun addMcpTools(mcpTools: List<com.aiassistant.agent.AgentTool>) {
        agent?.ctx?.toolRegistry?.registerMcp(mcpTools)
    }

    private fun setupCallbacks(a: AgentLoop) {
        a.onMessage = { msg -> runOnEdt { messages.add(msg); onMessagesChanged?.invoke() } }
        a.onStreaming = { text -> runOnEdt { streamingContent = text; onStreamingUpdate?.invoke(text) } }
        a.onToolExecute = { name, args -> runOnEdt { currentToolName = name; onToolExecute?.invoke(name, args); onMessagesChanged?.invoke() } }
        a.onToolResult = { name, result -> runOnEdt { currentToolName = null; onToolResult?.invoke(name, result); onMessagesChanged?.invoke() } }
        a.onPlanUpdate = { plan -> runOnEdt { currentPlan = plan; onPlanUpdate?.invoke(plan); onMessagesChanged?.invoke() } }
        a.onError = { msg -> runOnEdt { onError?.invoke(msg) } }
        a.onStateChange = { streaming -> runOnEdt { isStreaming = streaming; onStreamingStateChanged?.invoke(streaming) } }
        a.onThinking = { text -> runOnEdt { currentToolName = text } }
        a.onModelRouted = { model -> runOnEdt { currentModel = model } }
        a.onConfirmTool = { name, args, latch, result ->
            runOnEdt { onConfirmTool?.invoke(name, args, latch, result) }
        }
    }

    fun sendMessage(apiKey: String, content: String) {
        if (content.isBlank() || isStreaming || isRateLimited) return
        streamingContent = ""
        messages.add(AgentMessage("user", content))
        runOnEdt { onMessagesChanged?.invoke() }
        isStreaming = true
        runOnEdt { onStreamingStateChanged?.invoke(true) }

        val a = agent
        if (a != null) {
            a.run(content, apiKey) { finalText ->
                messages.add(AgentMessage("assistant", finalText))
            }
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater(action) else action()
    }

    fun stopGeneration() {
        agent?.stop()
        isStreaming = false
        streamingContent = ""
        runOnEdt { onStreamingStateChanged?.invoke(false) }
    }

    fun clearConversation() {
        stopGeneration()
        messages.clear()
        streamingContent = ""
        currentPlan = null
        isRateLimited = false
        runOnEdt { onMessagesChanged?.invoke() }
    }
}
