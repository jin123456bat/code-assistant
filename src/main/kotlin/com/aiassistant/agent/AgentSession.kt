package com.aiassistant.agent

import java.time.Instant
import java.util.UUID

// ponytail: 会话状态机 + 消息列表，事件发射延后到 ChatViewModel

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
        set(value) {
            field = value
            updatedAt = Instant.now()
        }

    val messages = mutableListOf<Message>()
    var cancelled = false
    val runningProcesses = mutableSetOf<Process>()
    var plan: com.aiassistant.agent.PlanExecutor.Plan? = null

    fun addMessage(msg: Message) {
        messages.add(msg)
        updatedAt = Instant.now()
    }

    fun cancel() {
        cancelled = true
        state = State.CANCELLED
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
