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
        val subject: String,
        val description: String = "",
        var status: StepStatus = StepStatus.PENDING,
        var result: String? = null
    )

    class Plan(
        val title: String,
        private val steps: MutableList<Step>
    ) {
        /** 线程安全地获取步骤快照（供 EDT 渲染使用） */
        fun stepsSnapshot(): List<Step> = synchronized(steps) { steps.toList() }

        fun progress(): String = synchronized(steps) {
            "${steps.count { it.status == StepStatus.DONE }}/${steps.size}"
        }
        fun isComplete(): Boolean = synchronized(steps) {
            steps.all { it.status == StepStatus.DONE }
        }
        fun nextPending(): Step? = synchronized(steps) {
            steps.firstOrNull { it.status == StepStatus.PENDING }
        }

        fun updateStep(index: Int, status: StepStatus, result: String? = null): Boolean {
            return synchronized(steps) {
                val step = steps.find { it.index == index }
                if (step != null) {
                    step.status = status
                    step.result = result
                    true
                } else {
                    false
                }
            }
        }

        /**
         * 追加新步骤到已有计划末尾，index 自动递增。
         * 设计决策：不设置步骤数量上限——LLM 通常先完成已有步骤再创建新步骤，
         * 且新会话会清空 Plan，实际不会无限增长。
         */
        fun appendSteps(newSteps: List<Step>) {
            synchronized(steps) {
                val startIndex = (steps.lastOrNull()?.index ?: 0) + 1
                newSteps.forEachIndexed { i, s ->
                    steps.add(s.copy(index = startIndex + i))
                }
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
    val images: List<ImageData>? = null,   // 用户粘贴的图片（Claude 原生 image 块格式）
    val id: Long = nextId()                // 消息唯一 ID，用于 messageRefChips 索引
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextId() = counter.incrementAndGet()
    }
}

data class ToolCallRequest(val id: String, val name: String, val arguments: String)

/** 图片数据：mediaType 如 "image/png"，data 为 base64 字符串 */
data class ImageData(val mediaType: String, val data: String)
