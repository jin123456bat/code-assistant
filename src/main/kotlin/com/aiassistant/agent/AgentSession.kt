package com.aiassistant.agent

import java.time.Instant
import java.util.UUID

class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新会话",
    /** 父 Session ID，子 Agent 独立持久化时通过此字段关联父 Session（对齐 docs/agent/multi-agent.md §二） */
    var parentId: String? = null
) {
    /** 是否为子 Agent 会话（parentId 非空时即为子 Agent） */
    val isSubAgent: Boolean get() = parentId != null

    enum class State {
        IDLE, PROCESSING, AWAITING_APPROVAL, EXECUTING, PAUSED, CANCELLED, ERROR
    }

    val createdAt: Instant = Instant.now()
    var updatedAt: Instant = createdAt

    var state: State = State.IDLE
        private set

    val messages = mutableListOf<Message>()
    var cancelled = false
    val runningProcesses = mutableSetOf<Process>()
    var plan: com.aiassistant.agent.PlanExecutor.Plan? = null

    /** 累计 token 消耗，按时间戳记录输入/输出 token 明细（对齐 docs/agent/loop.md §七 totalTokens: TokenUsage） */
    var totalTokens: TokenUsage = TokenUsage()

    /** compact 后的对话摘要（对齐 docs/agent/loop.md §七） */
    var compactSummary: String? = null

    /** 已执行 compact 的次数（对齐 docs/agent/loop.md §七） */
    var compactCount: Int = 0

    /** 累计错误次数，用于连续错误升级规则（对齐 docs/agent/loop.md §三） */
    var errorCount: Int = 0
        internal set

    /** 记录每个文件上次读取时的 modificationStamp，用于 Edit 冲突检测 */
    val fileStamps: MutableMap<String, Long> = mutableMapOf()

    /**
     * 当前 turn 中被 Read 过的文件路径集合。
     * 对齐 docs/agent/tools.md §七 前置校验：Edit/Write（覆盖模式）必须检查当前 turn 中该文件是否已被 Read 过。
     * 每个 turn 开始时由 AgentLoop 清空，Read 工具成功执行后添加路径。
     */
    val filesReadThisTurn: MutableSet<String> = mutableSetOf()

    /** 用户已批准此会话的工具名集合，持久化到 Session JSON 的 approvedTools 字段（对齐 docs/agent/tools.md §六 审批白名单） */
    val approvedTools: MutableSet<String> = mutableSetOf()

    /** 本会话中已完成首次审批的工具名集合，用于判断"首次工具使用"（对齐 docs/agent/tools.md §六 审批触发规则） */
    val firstToolUseDone: MutableSet<String> = mutableSetOf()

    /** 当前 turn 中已被 Write/Edit 修改过的文件路径集合，用于大范围修改检测（同一 turn ≥5 个文件）（对齐 docs/agent/tools.md §六） */
    val filesModifiedThisTurn: MutableSet<String> = mutableSetOf()

    /**
     * 本会话中已被调用过的 Skill 名称集合。
     * 对齐 docs/agent/skills.md §五：LLM 自动触发 Skill 后不再重复注入（避免 context 膨胀），
     * compact 时被调用过的 skill 重新注入。
     */
    val calledSkills: MutableSet<String> = mutableSetOf()

    fun addMessage(msg: Message) {
        messages.add(msg)
        updatedAt = Instant.now()
    }

    fun cancel() {
        cancelled = true
        state = State.CANCELLED
    }

    // ── 状态转换方法 ──

    fun startProcessing() {
        state = State.PROCESSING
    }

    fun requireApproval() {
        state = State.AWAITING_APPROVAL
    }

    fun approvalGranted() {
        state = State.EXECUTING
    }

    fun approvalRejected() {
        state = State.PROCESSING
    }

    fun startExecuting() {
        state = State.EXECUTING
    }

    fun doneExecuting() {
        if (state == State.EXECUTING) {
            state = State.PROCESSING  // 回到循环等待 LLM 下一轮
        }
    }

    fun finishTurn() {
        state = State.IDLE
    }

    fun pause() {
        state = State.PAUSED
    }

    fun resume() {
        state = State.PROCESSING
    }

    fun markError(error: String) {
        errorCount++
        state = State.ERROR
        addMessage(Message(role = Role.ERROR, content = error))
    }

    /**
     * 回退：标记指定 messageId 之后的所有消息 deleted=true。
     * 对齐 docs/agent/loop.md §七
     *
     * @return 被标记为 deleted 的消息数量
     */
    fun rollbackTo(messageId: String): Int {
        var found = false
        var rolledBack = 0
        for (msg in messages) {
            if (found) {
                msg.deleted = true
                rolledBack++
            }
            if (msg.id == messageId) {
                found = true
            }
        }
        return rolledBack
    }

    /**
     * 撤销回退：将所有标记为 deleted=true 的消息恢复为 deleted=false。
     * 对齐 docs/agent/session.md §七
     */
    fun undoRollback() {
        for (msg in messages) {
            if (msg.deleted) {
                msg.deleted = false
            }
        }
    }

    /**
     * 是否存在可撤销的回退。
     * 对齐 docs/agent/session.md §七
     */
    val hasPendingRollback: Boolean
        get() = messages.any { it.deleted }
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val contentType: ContentType? = null,
    val timestamp: Instant = Instant.now(),
    val toolCalls: List<ToolCallRecord>? = null,
    val tokenUsage: TokenDelta? = null,
    /** 用户反馈: "positive" | "negative"，仅 assistant 消息有此字段（对齐 docs/ui/chat.md §十） */
    var feedback: String? = null,
    /** 回退标记，true 时持久化保留但 UI 不渲染（对齐 docs/agent/loop.md §七） */
    var deleted: Boolean = false
)

enum class Role { USER, ASSISTANT, SYSTEM, ERROR }

enum class ContentType { TEXT, TOOL_USE, TOOL_RESULT }

data class ToolCallRecord(
    val id: String,
    val name: String,
    val parameters: Map<String, Any?>,
    val state: ToolCallState = ToolCallState.PENDING,
    val result: String? = null,
    val durationMs: Long? = null
)

enum class ToolCallState {
    PENDING, AWAITING_APPROVAL, EXECUTING, DONE, ERROR, TIMEOUT, REJECTED, CANCELLED
}

data class TokenDelta(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0
)

/**
 * 会话级 token 累计，记录输入/输出 token 明细及最后更新时间戳。
 * 对齐 docs/agent/loop.md §七 TokenUsage: inputTokens, outputTokens, timestamp
 */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val timestamp: Instant = Instant.now()
) {
    /** 输入+输出 token 总数 */
    val total: Long get() = inputTokens + outputTokens
}
