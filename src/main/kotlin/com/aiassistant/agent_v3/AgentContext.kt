package com.aiassistant.agent_v3

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

    // Plan mode
    var currentPlan: Plan? = null

    data class Step(
        val index: Int,
        val description: String,
        val status: StepStatus = StepStatus.PENDING,
        val result: String? = null
    )

    data class Plan(
        val title: String,
        val steps: List<Step>
    ) {
        fun progress(): String = "${steps.count { it.status == StepStatus.DONE }}/${steps.size}"
        fun isComplete(): Boolean = steps.all { it.status == StepStatus.DONE }
        fun nextPending(): Step? = steps.firstOrNull { it.status == StepStatus.PENDING }
    }

    enum class StepStatus { PENDING, IN_PROGRESS, DONE, FAILED }
}

data class AgentMessage(
    val role: String,     // system, user, assistant, tool
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRequest>? = null
)

data class ToolCallRequest(val id: String, val name: String, val arguments: String)
