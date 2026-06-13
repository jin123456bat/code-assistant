package com.aiassistant

import com.aiassistant.agent.AgentContext
import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentMessage
import com.aiassistant.agent.ImageData
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

class ChatViewModel(
    private val sseClient: SseClient = SseClient(),
    private val anthropicAdapter: AnthropicAdapter = AnthropicAdapter()
) {
    @Volatile var messages = mutableListOf<AgentMessage>()
    val messageCount: Int get() = messages.size
    @Volatile var streamingContent = ""
    @Volatile var streamingThinking = ""
    @Volatile var isStreaming = false
    @Volatile var isRateLimited = false
    @Volatile var currentToolName: String? = null
    /** 待审批工具: toolName → ApprovalState */
    @Volatile var pendingApprovals = mutableMapOf<String, ApprovalState>()
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
    var onStreamingThinkingChanged: ((String) -> Unit)? = null
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

    fun getSkillNames(): List<String> = agent?.ctx?.toolRegistry?.getAll()
        ?.filter { it.name !in setOf("search_code","read_file","write_file","list_directory","execute_command","git_diff","git_log","git_status","ask_user","web_search","web_fetch","notebook_edit","task") }
        ?.map { it.name } ?: emptyList()

    fun addMcpTools(mcpTools: List<com.aiassistant.agent.AgentTool>) {
        agent?.ctx?.toolRegistry?.registerMcp(mcpTools)
    }

    /** 添加 thinking 消息，若上一条也是 thinking 则合并，避免多个相邻思考行 */
    private fun addThinkingMessage(content: String) {
        val last = messages.lastOrNull()
        if (last != null && last.role == "thinking") {
            messages[messages.lastIndex] = AgentMessage("thinking", last.content + "\n" + content)
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
                }
                onMessagesChanged?.invoke()
            }
        }
        a.onStreaming = { text -> runOnEdt { streamingContent = text; onStreamingUpdate?.invoke(text) } }
        a.onThinkingDelta = { text -> runOnEdt {
            AppLogger.info("EDT onThinkingDelta thinking.len=${text.length} streamingContent.len=${streamingContent.length}")
            streamingThinking = text; onStreamingThinkingChanged?.invoke(text)
        } }
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
                    messages[idx] = messages[idx].copy(content = messages[idx].content + "\n---\n" + result, approvalPending = false)
                } else {
                    messages.add(AgentMessage("tool", result, toolName = name))
                }
                pendingApprovals.remove(name)  // 审批已完成，清理状态
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
                else { activity = Activity.Idle; currentToolName = null; streamingThinking = "" }
                onStreamingStateChanged?.invoke(streaming)
            }
        }
        a.onThinking = { text ->
            runOnEdt {
                // 仅"思考中"语义更新状态；null（清空）不再直接清状态，避免 null 间隙闪烁。
                // 不触发 onMessagesChanged，否则当前轮 streamingContent 还未清空时就 rebuild，
                // 导致同一份 AI 回复先作为流式气泡渲染一次，又被 callback 的 messages 渲染一次（重复显示）。
                if (text != null && text.contains("思考")) {
                    activity = Activity.Thinking
                }
            }
        }
        a.onModelRouted = { model -> runOnEdt { currentModel = model } }
        a.onConfirmTool = { name, args, latch, result ->
            runOnEdt {
                pendingApprovals[name] = ApprovalState(latch, result)
                // 标记 tool 消息为待审批
                val idx = messages.indexOfLast { it.role == "tool" && it.toolName == name }
                if (idx >= 0) messages[idx] = messages[idx].copy(approvalPending = true)
                onConfirmTool?.invoke(name, args, latch, result)
            }
        }
    }

    /**
     * @param content 用户输入文本（显示在气泡中）
     * @param images 粘贴的图片
     * @param refContent 文件引用的 Markdown 内容（仅发给 LLM，不显示在气泡中）
     */
    /** 发送用户消息，返回消息 ID（用于 messageRefChips 索引） */
    fun sendMessage(apiKey: String, content: String, images: List<ImageData>? = null, refContent: String = ""): Long {
        if (content.isBlank() || isStreaming || isRateLimited) return -1L
        streamingContent = ""
        streamingThinking = ""
        // 气泡只显示用户文本，引用内容以 chips 形式独立展示
        val msg = AgentMessage("user", content, images = images)
        messages.add(msg)
        runOnEdt { onMessagesChanged?.invoke() }
        isStreaming = true
        runOnEdt { onStreamingStateChanged?.invoke(true) }

        val llmContent = if (refContent.isNotEmpty()) "$content\n\n$refContent" else content
        val a = agent
        if (a != null) {
            a.run(llmContent, apiKey, images) { finalText, thinking ->
                // thinking 与 assistant 消息在同一个 EDT 块中原子性地落地，
                // 同时清空 streamingThinking/streamingContent，避免分两次 rebuild
                // 导致 streamingContent 作为临时气泡重复渲染。
                runOnEdt {
                    if (thinking.isNotEmpty()) {
                        addThinkingMessage(thinking)
                    }
                    if (finalText.isNotEmpty()) {
                        messages.add(AgentMessage("assistant", finalText))
                    }
                    streamingContent = ""
                    activity = Activity.Idle  // 必须在 onMessagesChanged 之前重置，否则 rebuildConversation 会渲染"思考中..."
                    onMessagesChanged?.invoke()
                }
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

    fun stopGeneration() {
        agent?.stop()
        isStreaming = false
        streamingContent = ""
        streamingThinking = ""
        runOnEdt { onStreamingStateChanged?.invoke(false) }
    }

    fun clearConversation() {
        stopGeneration()
        messages.clear()
        streamingContent = ""
        streamingThinking = ""
        currentPlan = null
        isRateLimited = false
        pendingApprovals.clear()
        runOnEdt { onMessagesChanged?.invoke() }
    }
}
