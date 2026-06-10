package com.aiassistant.agent_v3

/**
 * 计划模式 — 自动检测复杂任务，生成并跟踪执行计划。
 *
 * @deprecated 计划判定已改为 LLM 驱动（create_plan 元工具）。
 * AgentLoop 不再调用 shouldPlan()/generatePlan()，改由 LLM 自主决定是否创建计划。
 * buildPlanSummary() 保留供将来 UI 层使用。
 */
@Deprecated("计划判定已改为 LLM 驱动（create_plan 元工具），此类仅保留供参考")
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
        return planKeywords.any { keyword ->
            if (keyword[0].isLetter() && keyword[0].code < 0x4E00) {
                // 英文关键词用自然语言边界匹配，避免文件名/路径中的子串误触发
                // 例如 "build.gradle.kts" 中的 "build" 不应触发计划模式
                val regex = Regex("(?<![\\w/.\\-])${Regex.escape(keyword)}(?![\\w/.\\-])")
                regex.containsMatchIn(lower)
            } else {
                // 中文关键词：直接 contains（中文不存在嵌在英文单词中的问题）
                lower.contains(keyword)
            }
        }
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
