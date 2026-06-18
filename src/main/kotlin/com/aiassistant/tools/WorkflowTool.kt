package com.aiassistant.tools

import com.aiassistant.AppSettingsService
import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.AgentTypes
import com.aiassistant.agent.SubAgentRegistry
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 工作流编排工具：一次声明式创建多个并行/串行子 Agent，自动收割合并结果。
 * 复用 TaskTool 的基础设施（AgentLoop 创建、SubAgentRegistry 管理）。
 */
class WorkflowTool : AgentTool {
    override val name = "workflow"
    override val description = "并行执行多个子任务。传入 JSON 任务数组 [{description, prompt, subagent_type?}]，所有任务同时在后台执行，完成后返回聚合结果。用于审查代码、搜索多个模块等可并行的场景。"
    override val parameters = listOf(
        ToolParameter("tasks", "string", "JSON 数组，每项含 description（简短名称）、prompt（完整指令）、subagent_type（可选，默认 Explore）", required = true),
        ToolParameter("mode", "string", "执行模式：parallel（默认，并行）或 sequential（串行）", enum = listOf("parallel", "sequential"))
    )

    private data class TaskDef(val description: String, val prompt: String, val agentType: String)

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val tasksJson = params["tasks"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("tasks 不能为空")
        val mode = params["mode"] ?: "parallel"

        val apiKey = try { AppSettingsService.getInstance().getApiKey() }
            catch (_: Exception) { null }
            ?: return ToolResult.err("未配置 API Key")

        // 解析任务列表
        val tasks = try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val raw: List<Map<String, String>> = gson.fromJson(tasksJson, type)
            raw.map { m ->
                TaskDef(
                    description = m["description"]?.takeIf { it.isNotBlank() }
                        ?: return ToolResult.err("每个任务必须有 description"),
                    prompt = m["prompt"]?.takeIf { it.isNotBlank() }
                        ?: return ToolResult.err("每个任务必须有 prompt"),
                    agentType = m["subagent_type"] ?: "Explore"
                )
            }
        } catch (e: Exception) {
            return ToolResult.err("tasks JSON 解析失败: ${e.message}")
        }
        if (tasks.isEmpty()) return ToolResult.err("tasks 不能为空数组")
        if (tasks.size > 10) return ToolResult.err("最多支持 10 个并行任务")

        onProgress?.invoke("🚀 启动 ${tasks.size} 个${if (mode == "parallel") "并行" else "串行"}子任务\n\n")

        return if (mode == "sequential") {
            executeSequential(tasks, project, apiKey, onProgress)
        } else {
            executeParallel(tasks, project, apiKey, onProgress)
        }
    }

    private fun executeParallel(
        tasks: List<TaskDef>, project: Project, apiKey: String,
        onProgress: ((String) -> Unit)?
    ): ToolResult {
        val doneCount = AtomicInteger(0)
        val total = tasks.size
        val results = mutableListOf<String>()

        for (task in tasks) {
            val subId = "wf-${System.currentTimeMillis()}-${task.description.take(10)}"
            val childLoop = createChildLoop(project, task.agentType)
            SubAgentRegistry.register(subId, task.description, childLoop)

            val resultRef = AtomicReference<String>()
            val latch = CountDownLatch(1)

            java.util.concurrent.CompletableFuture.runAsync {
                try {
                    childLoop.run(task.prompt, apiKey) { finalText, _ ->
                        resultRef.set(finalText)
                        latch.countDown()
                    }
                    latch.await()
                    val result = resultRef.get()?.takeIf { it.isNotBlank() } ?: "(未返回结果)"
                    SubAgentRegistry.complete(subId, result)
                } catch (e: Exception) {
                    SubAgentRegistry.fail(subId, e.message ?: "未知错误")
                } finally {
                    childLoop.stop()
                }
            }

            val count = doneCount.incrementAndGet()
            onProgress?.invoke("  📤 ${task.description} — 已启动 ($count/$total)\n")
        }

        // 等待所有子任务完成（最长 10 分钟）
        onProgress?.invoke("\n⏳ 等待 ${total} 个子任务完成...\n")
        val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
        while (doneCount.get() > 0 || results.size < total) {
            val completed = SubAgentRegistry.drainCompleted()
            for (entry in completed) {
                val icon = if (entry.status == SubAgentRegistry.Status.DONE) "✅" else "❌"
                val text = when (entry.status) {
                    SubAgentRegistry.Status.DONE -> entry.result ?: ""
                    SubAgentRegistry.Status.FAILED -> "失败: ${entry.error}"
                    else -> null
                } ?: continue
                results.add("$icon **${entry.description}**\n$text")
                onProgress?.invoke("  $icon ${entry.description} — 已完成 (${results.size}/$total)\n")
            }
            if (results.size >= total) break
            if (System.currentTimeMillis() > deadline) {
                results.add("⚠ 超时：${total - results.size} 个子任务未完成")
                break
            }
            Thread.sleep(500)
        }

        return ToolResult.ok("## 工作流完成（${results.size}/$total）\n\n" + results.joinToString("\n\n---\n\n"))
    }

    private fun executeSequential(
        tasks: List<TaskDef>, project: Project, apiKey: String,
        onProgress: ((String) -> Unit)?
    ): ToolResult {
        val results = mutableListOf<String>()
        for ((i, task) in tasks.withIndex()) {
            onProgress?.invoke("  🔄 ${task.description} — 执行中 (${i + 1}/${tasks.size})\n")
            val childLoop = createChildLoop(project, task.agentType)
            val resultRef = AtomicReference<String>()
            val latch = CountDownLatch(1)
            try {
                childLoop.run(task.prompt, apiKey) { finalText, _ ->
                    resultRef.set(finalText)
                    latch.countDown()
                }
                latch.await(5, TimeUnit.MINUTES)
                val result = resultRef.get()?.takeIf { it.isNotBlank() } ?: "(未返回结果)"
                results.add("✅ **${task.description}**\n$result")
            } catch (e: Exception) {
                results.add("❌ **${task.description}**\n失败: ${e.message}")
            } finally {
                childLoop.stop()
            }
        }
        return ToolResult.ok("## 串行工作流完成（${results.size}/${tasks.size}）\n\n" + results.joinToString("\n\n---\n\n"))
    }

    private fun createChildLoop(project: Project, agentTypeName: String): AgentLoop {
        val agentType = AgentTypes.find(agentTypeName)
        val childLoop = AgentLoop(project)
        childLoop.initialize(
            mcpTools = emptyList(),
            allowedTools = agentType.allowedTools,
            deniedTools = agentType.deniedTools,
            asSubAgent = true,
            overrideMaxLoops = agentType.maxLoops
        )
        childLoop.onConfirmTool = { _, _, latch, choice ->
            choice.set(agentType.autoApprove)
            latch.countDown()
        }
        return childLoop
    }
}
