package com.aiassistant.agent

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
        var status: StepStatus = StepStatus.PENDING,
        var result: String? = null
    )

    class Plan(
        val title: String,
        val steps: List<Step>
    ) {
        fun progress(): String = "${steps.count { it.status == StepStatus.DONE }}/${steps.size}"
        fun isComplete(): Boolean = steps.all { it.status == StepStatus.DONE }
        fun nextPending(): Step? = steps.firstOrNull { it.status == StepStatus.PENDING }

        fun updateStep(index: Int, status: StepStatus, result: String? = null) {
            steps.find { it.index == index }?.let {
                it.status = status
                it.result = result
            }
        }
    }

    enum class StepStatus { PENDING, IN_PROGRESS, DONE, FAILED }
}

data class AgentMessage(
    val role: String,     // system, user, assistant, tool, tool_call
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<ToolCallRequest>? = null,
    val approvalPending: Boolean = false,  // 待审批状态
    val images: List<ImageData>? = null    // 用户粘贴的图片（Claude 原生 image 块格式）
)

data class ToolCallRequest(val id: String, val name: String, val arguments: String)

/** 图片数据：mediaType 如 "image/png"，data 为 base64 字符串 */
data class ImageData(val mediaType: String, val data: String)
