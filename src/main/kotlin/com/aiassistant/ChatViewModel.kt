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
    /**
     * Agent 当前活动状态 —— 单点表达 UI 指示器该显示什么，
     * 取代以前用 currentToolName 一字段三义（工具名/思考文本/null）+ 字符串嗅探，
     * 那是"执行中指示器闪烁"的根因。
     */
    @Volatile var activity: Activity = Activity.Idle
    @Volatile var currentPlan: AgentContext.Plan? = null
    @Volatile var currentModel: String = "deepseek-chat"

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
        a.onToolExecute = { name, args ->
            runOnEdt {
                activity = Activity.RunningTool(name)
                currentToolName = name
                onToolExecute?.invoke(name, args); onMessagesChanged?.invoke()
            }
        }
        a.onToolResult = { name, result ->
            runOnEdt {
                // 工具结束后 agent 仍在循环 → 回到"思考中"而非 Idle，避免指示器消失再出现的闪烁
                activity = Activity.Thinking
                currentToolName = null
                onToolResult?.invoke(name, result); onMessagesChanged?.invoke()
            }
        }
        a.onPlanUpdate = { plan -> runOnEdt { currentPlan = plan; onPlanUpdate?.invoke(plan); onMessagesChanged?.invoke() } }
        a.onError = { msg -> runOnEdt { onError?.invoke(msg) } }
        a.onStateChange = { streaming ->
            runOnEdt {
                isStreaming = streaming
                // 运行开始→思考中；结束→空闲
                if (streaming) { if (activity == Activity.Idle) activity = Activity.Thinking }
                else { activity = Activity.Idle; currentToolName = null }
                onStreamingStateChanged?.invoke(streaming)
            }
        }
        a.onThinking = { text ->
            runOnEdt {
                // 仅"思考中"语义更新状态；null（清空）不再直接清状态，避免 null 间隙闪烁
                if (text != null && text.contains("思考")) {
                    activity = Activity.Thinking
                }
                onMessagesChanged?.invoke()
            }
        }
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
                // 在 EDT 上落地最终文本，并清空 streamingContent，
                // 否则 messages 与 streamingContent 会同时含同一份内容 → 重复渲染。
                runOnEdt {
                    if (finalText.isNotEmpty()) {
                        messages.add(AgentMessage("assistant", finalText))
                    }
                    streamingContent = ""
                    onMessagesChanged?.invoke()
                }
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
