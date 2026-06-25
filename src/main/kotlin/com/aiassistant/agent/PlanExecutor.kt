package com.aiassistant.agent

// ponytail: Plan Mode shell — generates plan then step-by-step execution via AgentLoop

class PlanExecutor(private val session: AgentSession) {

    data class Plan(
        val id: String = java.util.UUID.randomUUID().toString(),
        val summary: String,
        val steps: List<PlanStep>,
        var status: Status = Status.PAUSED,
        var currentStepIndex: Int = 0
    ) {
        enum class Status { PAUSED, EXECUTING, COMPLETED, CANCELLED }
    }

    data class PlanStep(
        val id: String = java.util.UUID.randomUUID().toString(),
        val description: String, val tool: String, val files: List<String>,
        var status: StepStatus = StepStatus.PENDING, var result: String? = null,
        val fileStamps: MutableMap<String, Long> = mutableMapOf(), var retryCount: Int = 0
    ) {
        enum class StepStatus { PENDING, EXECUTING, DONE, ERROR, SKIPPED, CANCELLED }
    }

    var currentPlan: Plan? = null

    fun parsePlan(llmOutput: String): Plan {
        // Layer 1: try JSON extraction (```json ... ``` or raw {...})
        val jsonSteps = tryParseJson(llmOutput)
        if (jsonSteps != null) return Plan(summary = llmOutput.take(80), steps = jsonSteps)

        // Layer 2: raw {...} search
        val rawJson =
            Regex("""\{[^}]*"steps"[^}]*\}""", RegexOption.DOT_MATCHES_ALL).find(llmOutput)
        if (rawJson != null) {
            val parsed = tryParseJson(rawJson.value)
            if (parsed != null) return Plan(summary = llmOutput.take(80), steps = parsed)
        }

        // Layer 3: numbered-line parser
        val lines = llmOutput.lines()
        val steps = mutableListOf<PlanStep>()
        for (line in lines) {
            val match =
                Regex("""^(?:\d+[.\)]\s*|Step\s*\d+[:\-]?\s*|步骤\s*\d+[:\-]?\s*)(.+)""").find(line.trim())
            if (match != null) {
                val desc = match.groupValues[1].trim()
                val tool = when {
                    desc.contains("读取") || desc.contains("read", ignoreCase = true) -> "readFile"
                    desc.contains("修改") || desc.contains("替换") || desc.contains(
                        "edit",
                        ignoreCase = true
                    ) -> "editFile"

                    desc.contains("编译") || desc.contains("测试") || desc.contains("运行") -> "runShell"
                    desc.contains("搜索") || desc.contains("找到") -> "searchContent"
                    else -> "readFile"
                }
                steps.add(PlanStep(description = desc, tool = tool, files = emptyList()))
            }
        }

        // Layer 4: raw text fallback
        if (steps.isEmpty()) {
            steps.add(
                PlanStep(
                    description = llmOutput.take(500),
                    tool = "unknown",
                    files = emptyList()
                )
            )
        }
        return Plan(summary = llmOutput.take(80), steps = steps)
    }

    private fun tryParseJson(text: String): List<PlanStep>? {
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
                PlanStep(
                    description = s.description,
                    tool = s.tool ?: "readFile",
                    files = s.files ?: emptyList()
                )
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun resumeNextStep(): PlanStep? {
        val plan = currentPlan ?: return null
        if (plan.currentStepIndex >= plan.steps.size) {
            plan.status = Plan.Status.COMPLETED; return null
        }
        val step = plan.steps[plan.currentStepIndex]
        step.status = PlanStep.StepStatus.DONE
        plan.currentStepIndex++
        if (plan.currentStepIndex >= plan.steps.size) plan.status = Plan.Status.COMPLETED
        return step
    }

    fun skipStep(stepId: String) {
        currentPlan?.steps?.find { it.id == stepId }?.status = PlanStep.StepStatus.SKIPPED
    }

    fun abortPlan() {
        currentPlan?.status = Plan.Status.CANCELLED
    }
}
