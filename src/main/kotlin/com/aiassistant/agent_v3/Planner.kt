package com.aiassistant.agent_v3

/**
 * 计划模式 — 自动检测复杂任务，生成并跟踪执行计划。
 * 触发条件：输入超过 200 字符，或包含"实现/重构/搭建/创建"等关键词。
 */
object Planner {

    private val planKeywords = listOf(
        "实现", "重构", "搭建", "创建", "开发", "设计", "架构",
        "优化", "迁移", "升级", "集成", "部署",
        "implement", "refactor", "build", "create", "develop",
        "design", "architecture", "optimize", "migrate", "upgrade",
        "integrate", "deploy"
    )

    fun shouldPlan(userInput: String): Boolean {
        val trimmed = userInput.trim()
        if (trimmed.length > 200) return true
        val lower = trimmed.lowercase()
        return planKeywords.any { lower.contains(it) }
    }

    fun generatePlan(userInput: String): AgentContext.Plan {
        // 简化计划生成：将输入按换行/分号/句号拆分，过滤空行
        val rawSteps = userInput
            .split("\n", ";", "。", ".", "；")
            .map { it.trim() }
            .filter { it.length > 5 }

        val steps = if (rawSteps.size >= 3) {
            rawSteps.mapIndexed { i, desc ->
                AgentContext.Step(index = i + 1, description = desc)
            }
        } else {
            // 自动拆分
            val subSteps = listOf(
                "分析需求和现有代码",
                "制定实现方案",
                "逐步实现并测试",
                "验证结果并总结"
            )
            subSteps.mapIndexed { i, desc ->
                AgentContext.Step(index = i + 1, description = desc)
            }
        }

        return AgentContext.Plan(
            title = userInput.take(80).let { if (userInput.length > 80) "$it..." else it },
            steps = steps
        )
    }

    fun buildPlanSummary(plan: AgentContext.Plan): String {
        return buildString {
            append("## 📋 执行计划: ${plan.title}\n\n")
            plan.steps.forEach { step ->
                val icon = when (step.status) {
                    AgentContext.StepStatus.DONE -> "✅"
                    AgentContext.StepStatus.IN_PROGRESS -> "🔄"
                    AgentContext.StepStatus.FAILED -> "❌"
                    AgentContext.StepStatus.PENDING -> "  "
                }
                append("$icon ${step.index}. ${step.description}")
                if (step.result != null) append(" — ${step.result}")
                append("\n")
            }
            append("\n进度: ${plan.progress()}")
        }
    }
}
