package com.aiassistant.agent

import com.aiassistant.AnthropicMessage
import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project

/**
 * Agent 共享上下文 — 贯穿整个 agent 生命周期。
 */
class AgentContext(val project: Project) {
    val toolRegistry = ToolRegistryV3()
    var systemPrompt: String = ""
    var model: String = "deepseek-chat"
    /** 跨轮对话历史：保留完整 assistant/tool 消息，使 LLM 能感知之前的所有交互 */
    @Volatile var conversationHistory = java.util.Collections.synchronizedList(mutableListOf<AnthropicMessage>())
        private set
    /** conversationHistory 的复合操作锁（clear+addAll 组合操作需要外部同步） */
    val historyLock = Any()
    /** 最近一次 API 调用的 input tokens（从 API usage 获取），用于判断是否触发自动 Compact */
    @Volatile var lastInputTokens: Int = 0
    @Volatile var lastOutputTokens: Int = 0

    data class RoundToken(val inputTokens: Int, val outputTokens: Int, val timestamp: Long = System.currentTimeMillis())
    class TokenStats {
        @Volatile var totalInput: Long = 0
        @Volatile var totalOutput: Long = 0
        @Volatile var roundCount: Int = 0
        val perRound: MutableList<RoundToken> = java.util.concurrent.CopyOnWriteArrayList()
    }
    val tokenStats = TokenStats()

    // ---- Plan Mode（对齐 Claude Code EnterPlanMode/ExitPlanMode） ----

    /** 规划模式：Agent 进入只读研究模式，禁止写操作 */
    @Volatile var planMode: Boolean = false
    /** 已批准的计划标题（PlanBar 摘要行显示） */
    @Volatile var approvedPlanTitle: String? = null
    /** 已批准的计划全文（markdown，PlanBar 展开后显示） */
    @Volatile var approvedPlan: String? = null

    // ---- Task System（对齐 Claude Code TaskCreate/TaskUpdate/TaskList/TaskGet） ----

    /** 任务列表（线程安全），替换旧 Plan/Step 模型 */
    val tasks: MutableList<Task> = java.util.concurrent.CopyOnWriteArrayList()

    /** 目标驱动模式：设置后 Agent 持续工作直到目标达成（用户中断或 MAX_LOOPS） */
    @Volatile var goal: String? = null

    val rules = mutableListOf<RuleDef>()

    data class RuleDef(
        val name: String,
        val description: String,
        val paths: String? = null,
        val content: String
    )

    /** 所有已加载的 skill 定义（名称 → SkillDef），不包括已激活的 skill */
    val skillDefs = mutableMapOf<String, SkillEngine.SkillDef>()
    /** 客户端通过 /skill-name 激活的 skill（此 skill 不会作为工具暴露给 LLM，防止重复调用） */
    @Volatile var activatedSkill: String? = null
    /** 激活的 skill 的完整 prompt（注入 system prompt，对齐 Claude Code） */
    @Volatile var activatedSkillPrompt: String? = null

    /** MCP prompts（对齐 Claude Code：MCP 服务器提供的 prompt 模板，注入 system prompt） */
    val mcpPrompts = java.util.concurrent.CopyOnWriteArrayList<com.aiassistant.mcp.McpPromptDef>()
    /** MCP resources（对齐 Claude Code：MCP 服务器提供的资源 URI 列表，注入 system prompt） */
    val mcpResources = java.util.concurrent.CopyOnWriteArrayList<com.aiassistant.mcp.McpResourceDef>()
}

// ---- Task Model（对齐 Claude Code） ----

data class Task(
    val id: Int,
    val subject: String,
    val description: String = "",
    var status: TaskStatus = TaskStatus.PENDING,
    var result: String? = null
)

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED }

// ---- 消息模型（跨 round 共享，AgentLoop/ChatViewModel/UI 共用） ----

data class AgentMessage(
    val role: String,     // system, user, assistant, tool, tool_call
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRequest>? = null,
    val approvalPending: Boolean = false,  // 待审批状态
    val images: List<ImageData>? = null,   // 用户粘贴的图片（Claude 原生 image 块格式）
    val id: Long = nextId(),               // 消息唯一 ID，用于 messageRefChips 索引
    val version: Int = 0,                   // 消息版本号：原地更新（copy）时递增，用于增量渲染变更检测
    val inputTokens: Int = 0,               // 本条消息消耗的 input tokens（仅 assistant 消息有意义）
    val outputTokens: Int = 0               // 本条消息消耗的 output tokens（仅 assistant 消息有意义）
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextId() = counter.incrementAndGet()
    }
}

data class ToolCallRequest(val id: String, val name: String, val arguments: String)

/** 图片数据：mediaType 如 "image/png"，data 为 base64 字符串 */
data class ImageData(val mediaType: String, val data: String)
