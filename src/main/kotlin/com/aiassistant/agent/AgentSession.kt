package com.aiassistant.agent

import java.time.Instant
import java.util.UUID

class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新会话"
) {
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

    /** 记录每个文件上次读取时的 modificationStamp，用于 Edit 冲突检测 */
    val fileStamps: MutableMap<String, Long> = mutableMapOf()

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
        state = State.ERROR
        addMessage(Message(role = Role.ERROR, content = error))
    }
}

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val timestamp: Instant = Instant.now(),
    val toolCalls: List<ToolCallRecord>? = null,
    val tokenUsage: TokenDelta? = null
)

enum class Role { USER, ASSISTANT, SYSTEM, TOOL_CALL, TOOL_RESULT, ERROR }

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
