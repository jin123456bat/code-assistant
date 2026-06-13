package com.aiassistant.tools

import com.aiassistant.*
import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.AgentLoop
import com.intellij.openapi.project.Project
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 子 Agent 工具。spawn 独立 AgentLoop 执行子任务，不污染主对话 history。
 */
class TaskTool : AgentTool {
    override val name = "task"
    override val description = "创建子 Agent 并行执行独立任务。适用于可分解的多步骤工作。"
    override val parameters = listOf(
        ToolParameter("description", "string", "子任务简短描述（3-5 字）", required = true),
        ToolParameter("prompt", "string", "子 Agent 的完整任务指令", required = true),
        ToolParameter("subagent_type", "string", "子 Agent 类型，默认 general-purpose")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val description = params["description"] ?: return ToolResult.err("缺少 description 参数")
        val prompt = params["prompt"] ?: return ToolResult.err("缺少 prompt 参数")

        val apiKey = try { AppSettingsService.getInstance().getApiKey() }
            catch (_: Exception) { null }
            ?: return ToolResult.err("未配置 API Key")

        // 创建独立 AgentLoop 实例
        val childLoop = AgentLoop(project)
        childLoop.initialize()

        val resultRef = AtomicReference<String>()
        val latch = CountDownLatch(1)

        try {
            childLoop.run(prompt, apiKey) { finalText, _ ->
                resultRef.set(finalText)
                latch.countDown()
            }

            val finished = latch.await(5, TimeUnit.MINUTES)
            if (!finished) {
                return ToolResult.err("子 Agent 执行超时（超过 5 分钟），任务: $description")
            }
            val result = resultRef.get() ?: ""
            return if (result.isBlank()) {
                ToolResult.err("子 Agent 未返回结果")
            } else {
                ToolResult.ok("子任务完成: $description\n\n$result")
            }
        } catch (e: Exception) {
            return ToolResult.err("子 Agent 执行失败: ${e.message}")
        }
    }
}
