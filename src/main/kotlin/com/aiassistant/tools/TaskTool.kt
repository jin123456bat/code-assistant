package com.aiassistant.tools

import com.aiassistant.*
import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.AgentType
import com.aiassistant.agent.AgentTypes
import com.aiassistant.agent.AgentLoop
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 子 Agent 工具。根据 AgentType 创建独立 AgentLoop 执行子任务，
 * 支持工具集定制、模型覆盖、前台/后台运行模式。
 * 子 Agent 不污染主对话 history，结果以 tool_result 返回。
 */
class TaskTool : AgentTool {
    override val name = "task"
    override val description = "创建子代理执行独立任务。可指定类型（general-purpose/Explore/Plan）、模型、后台运行"
    override val parameters = listOf(
        ToolParameter("description", "string", "子任务简短描述（3-5 字）", required = true),
        ToolParameter("prompt", "string", "子代理的完整任务指令", required = true),
        ToolParameter("subagent_type", "string", "子代理类型：general-purpose（默认）/ Explore（只读搜索）/ Plan（纯规划）"),
        ToolParameter("model", "string", "模型覆盖（不传则继承主代理模型）"),
        ToolParameter("background", "string", "是否后台运行（true/false，默认 false）")
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val description = params["description"] ?: return ToolResult.err("缺少 description 参数")
        val prompt = params["prompt"] ?: return ToolResult.err("缺少 prompt 参数")
        val background = params["background"]?.lowercase() == "true"

        val agentType = AgentTypes.find(params["subagent_type"])
        val overrideModel = params["model"]?.takeIf { it.isNotBlank() }

        val apiKey = try { AppSettingsService.getInstance().getApiKey() }
            catch (_: Exception) { null }
            ?: return ToolResult.err("未配置 API Key")

        // 创建子 AgentLoop，传工具白名单
        val childLoop = AgentLoop(project)
        childLoop.initialize(
            mcpTools = emptyList(),
            allowedTools = agentType.allowedTools,
            deniedTools = agentType.deniedTools,
            asSubAgent = true
        )

        // 模型覆盖
        if (overrideModel != null) {
            childLoop.switchModel(overrideModel)
        }

        // 子 Agent 自动批准工具（免审批），非 autoApprove 时拒绝非 SAFE_TOOLS
        childLoop.onConfirmTool = { toolName, _, latch, choice ->
            if (agentType.autoApprove) {
                choice.set(true)
            } else {
                choice.set(false)  // 拒绝非安全工具
            }
            latch.countDown()
        }

        // 工具执行回调 — 实时推送到主 Agent UI
        val toolLog = StringBuilder()
        childLoop.onToolExecute = { name, args ->
            val line = "[执行 $name]\n"
            toolLog.append(line)
            onProgress?.invoke(line)
        }
        childLoop.onToolResult = { name, result ->
            val line = "[结果 $name] ${result.take(200)}\n\n"
            toolLog.append(line)
            onProgress?.invoke(line)
        }
        childLoop.onStreaming = { text ->
            // 子 Agent 流式输出实时推送
            onProgress?.invoke(text)
        }

        val resultRef = AtomicReference<String>()
        val thinkingRef = AtomicReference<String>()
        val latch = CountDownLatch(1)

        return try {
            if (background) {
                // 后台模式：异步执行，立即返回
                CompletableFuture.runAsync {
                    try {
                        childLoop.run(prompt, apiKey) { finalText, thinking ->
                            resultRef.set(finalText)
                            thinkingRef.set(thinking)
                            latch.countDown()
                        }
                        latch.await(agentType.timeoutMinutes, TimeUnit.MINUTES)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        childLoop.stop()
                    }
                }
                ToolResult.ok("子代理已启动（后台运行）: $description\n\n类型: ${agentType.name} | 超时: ${agentType.timeoutMinutes}min")
            } else {
                // 前台模式：阻塞等待结果
                childLoop.run(prompt, apiKey) { finalText, thinking ->
                    resultRef.set(finalText)
                    thinkingRef.set(thinking)
                    latch.countDown()
                }

                val finished = try {
                    latch.await(agentType.timeoutMinutes, TimeUnit.MINUTES)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }

                if (!finished) {
                    childLoop.stop()
                    return ToolResult.err("子代理执行超时（${agentType.timeoutMinutes} 分钟）: $description")
                }

                val result = resultRef.get().takeIf { it.isNotBlank() } ?: ""
                if (result.isBlank()) {
                    ToolResult.err("子代理未返回结果: $description")
                } else {
                    val logSection = if (toolLog.isNotEmpty()) "\n\n---\n### 工具调用\n$toolLog" else ""
                    ToolResult.ok("子任务完成: $description\n\n$result$logSection")
                }
            }
        } catch (e: Exception) {
            ToolResult.err("子代理执行失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (!background) {
                childLoop.stop()
            }
        }
    }
}
