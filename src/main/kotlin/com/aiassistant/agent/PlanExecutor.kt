package com.aiassistant.agent

import java.time.Instant

// ponytail: plan executor — generates plan then step-by-step execution via AgentLoop, controlled by PlanCard buttons

class PlanExecutor(private val session: AgentSession) {

    data class Plan(
        val id: String = java.util.UUID.randomUUID().toString(),
        val summary: String,
        val parentId: String? = null,
        val plans: MutableList<PlanItem>,
        var status: Status = Status.PAUSED,
        var currentPlanIndex: Int = 0,
        val createdAt: Instant = Instant.now(),
        var updatedAt: Instant = createdAt
    ) {
        enum class Status { PAUSED, EXECUTING, COMPLETED, CANCELLED }
    }

    data class PlanItem(
        val id: String = java.util.UUID.randomUUID().toString(),
        val description: String, val tool: String, val files: List<String>,
        var status: ItemStatus = ItemStatus.PAUSED, var result: String? = null,
        var retryCount: Int = 0
    ) {
        enum class ItemStatus { PAUSED, EXECUTING, COMPLETED, CANCELLED }
    }

    var currentPlan: Plan? = null

    fun parsePlan(llmOutput: String): Plan {
        // Layer 1: try JSON extraction (```json ... ``` or raw {...})
        val jsonPlans = tryParseJson(llmOutput)
        if (jsonPlans != null) return Plan(
            summary = llmOutput.take(80),
            plans = jsonPlans.toMutableList()
        )

        // Layer 2: raw {...} search
        val rawJson =
            Regex("""\{[^}]*"steps"[^}]*\}""", RegexOption.DOT_MATCHES_ALL).find(llmOutput)
        if (rawJson != null) {
            val parsed = tryParseJson(rawJson.value)
            if (parsed != null) return Plan(
                summary = llmOutput.take(80),
                plans = parsed.toMutableList()
            )
        }

        // Layer 3: numbered-line parser
        val lines = llmOutput.lines()
        val items = mutableListOf<PlanItem>()
        for (line in lines) {
            val match =
                Regex("""^(?:\d+[.\)]\s*|Step\s*\d+[:\-]?\s*|步骤\s*\d+[:\-]?\s*)(.+)""").find(line.trim())
            if (match != null) {
                val desc = match.groupValues[1].trim()
                val tool = when {
                    desc.contains("读取") || desc.contains("read", ignoreCase = true) -> "Read"
                    desc.contains("修改") || desc.contains("替换") || desc.contains(
                        "edit",
                        ignoreCase = true
                    ) -> "Edit"

                    desc.contains("编译") || desc.contains("测试") || desc.contains("运行") -> "Bash"
                    desc.contains("搜索") || desc.contains("找到") -> "Grep"
                    else -> "Read"
                }
                items.add(PlanItem(description = desc, tool = tool, files = emptyList()))
            }
        }

        // Layer 4: raw text fallback
        if (items.isEmpty()) {
            items.add(
                PlanItem(
                    description = llmOutput.take(500),
                    tool = "unknown",
                    files = emptyList()
                )
            )
        }
        return Plan(summary = llmOutput.take(80), plans = items)
    }

    private fun tryParseJson(text: String): List<PlanItem>? {
        return try {
            // Extract ```json ... ``` block or raw JSON
            val jsonBlock = Regex(
                """```(?:json)?\s*\n?(.*?)\n?```""",
                RegexOption.DOT_MATCHES_ALL
            ).find(text)?.groupValues?.get(1) ?: text
            val gson = com.google.gson.Gson()

            data class StepJson(
                val description: String,
                val tool: String? = null,
                val files: List<String>? = null
            )

            val list: List<StepJson> =
                gson.fromJson(jsonBlock, Array<StepJson>::class.java).toList()
            list.map { s ->
                PlanItem(
                    description = s.description,
                    tool = s.tool ?: "Read",
                    files = s.files ?: emptyList()
                )
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun resumeNextStep(): PlanItem? {
        val plan = currentPlan ?: return null
        if (plan.currentPlanIndex >= plan.plans.size) {
            plan.status = Plan.Status.COMPLETED; return null
        }
        val item = plan.plans[plan.currentPlanIndex]
        item.status = PlanItem.ItemStatus.COMPLETED
        plan.currentPlanIndex++
        if (plan.currentPlanIndex >= plan.plans.size) plan.status = Plan.Status.COMPLETED
        return item
    }

    fun skipStep(itemId: String) {
        currentPlan?.plans?.find { it.id == itemId }?.status = PlanItem.ItemStatus.CANCELLED
    }

    fun abortPlan() {
        currentPlan?.status = Plan.Status.CANCELLED
    }

    // ── LLM 工具方法：5 个计划管理操作 ──

    /** createPlan: 创建/更新执行计划。LLM 通过 createPlan 工具主动创建 */
    fun createPlanFromTool(task: String, plans: List<Map<String, Any?>>): Plan {
        val items = plans.map { item ->
            val desc = item["description"] as? String ?: ""
            val tool = item["tool"] as? String ?: "Read"

            val files = (item["files"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            PlanItem(description = desc, tool = tool, files = files)
        }.toMutableList()
        val plan = Plan(summary = task, plans = items, status = Plan.Status.PAUSED)
        currentPlan = plan
        session.plan = plan
        return plan
    }

    /** listPlans: 查看当前计划的所有计划项及状态 */
    fun listPlans(): List<PlanItem> {
        return currentPlan?.plans ?: emptyList()
    }

    /** removePlan: 删除指定计划项（仅 PAUSED 状态可删） */
    fun removePlan(planId: String): String {
        val plan = currentPlan ?: return "错误: 当前没有活跃计划"
        val item = plan.plans.find { it.id == planId }
            ?: return "错误: 未找到 ID 为 $planId 的计划项"
        if (item.status != PlanItem.ItemStatus.PAUSED) {
            return "错误: 计划项 $planId 状态为 ${item.status}，只有 PAUSED 状态可删除"
        }
        item.status = PlanItem.ItemStatus.CANCELLED
        // 如果删除的是当前项，跳过它
        if (plan.currentPlanIndex < plan.plans.size && plan.plans[plan.currentPlanIndex].id == planId) {
            plan.currentPlanIndex++
        }
        return "已删除计划项: $planId (${item.description})"
    }

    /** reorderPlans: 重排剩余 PAUSED 计划项的执行顺序 */
    fun reorderPlans(planIds: List<String>): String {
        val plan = currentPlan ?: return "错误: 当前没有活跃计划"
        val currentItem = if (plan.currentPlanIndex < plan.plans.size)
            plan.plans[plan.currentPlanIndex] else null
        val remainingItems = plan.plans.filter { it.status == PlanItem.ItemStatus.PAUSED }
        val reordered = mutableListOf<PlanItem>()
        for (id in planIds) {
            val item = remainingItems.find { it.id == id }
                ?: return "错误: 未找到 ID 为 $id 的 PAUSED 计划项"
            reordered.add(item)
        }
        // 保持非 PAUSED 项在原位，替换 PAUSED 项为新顺序
        val newItems = mutableListOf<PlanItem>()
        var reorderedIdx = 0
        for (item in plan.plans) {
            if (item.status == PlanItem.ItemStatus.PAUSED) {
                if (reorderedIdx < reordered.size) {
                    newItems.add(reordered[reorderedIdx])
                    reorderedIdx++
                }
            } else {
                newItems.add(item)
            }
        }
        // 替换 plans 列表
        plan.plans.clear()
        plan.plans.addAll(newItems)
        // 重新定位 currentPlanIndex
        plan.currentPlanIndex = if (currentItem != null) {
            plan.plans.indexOfFirst { it.id == currentItem.id }.coerceAtLeast(0)
        } else {
            plan.plans.size
        }
        return "已重排计划项顺序: ${planIds.joinToString(", ")}"
    }

    /** markPlanDone: 将指定计划项标记为 COMPLETED */
    fun markPlanDone(planId: String): String {
        val plan = currentPlan ?: return "错误: 当前没有活跃计划"
        val item = plan.plans.find { it.id == planId }
            ?: return "错误: 未找到 ID 为 $planId 的计划项"
        item.status = PlanItem.ItemStatus.COMPLETED
        // 如果是当前项，自动推进 currentPlanIndex
        if (plan.currentPlanIndex < plan.plans.size && plan.plans[plan.currentPlanIndex].id == planId) {
            plan.currentPlanIndex++
        }
        // 检查是否全部完成
        if (plan.plans.all { it.status == PlanItem.ItemStatus.COMPLETED || it.status == PlanItem.ItemStatus.CANCELLED }) {
            plan.status = Plan.Status.COMPLETED
        }
        return "已将计划项 $planId 标记为 COMPLETED: ${item.description}"
    }

    /** deletePlan: [内部] 终止计划，所有剩余项标记 CANCELLED */
    fun deletePlan() {
        val plan = currentPlan ?: return
        plan.plans.filter { it.status == PlanItem.ItemStatus.PAUSED }.forEach {
            it.status = PlanItem.ItemStatus.CANCELLED
        }
        plan.status = Plan.Status.CANCELLED
    }
}
