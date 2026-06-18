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
            asSubAgent = true,
            overrideMaxLoops = agentType.maxLoops
        )

        // 模型覆盖
        if (overrideModel != null) {
            childLoop.switchModel(overrideModel)
        }

        // Worktree 隔离：为子 Agent 创建独立 git worktree
        val workTreePath: String? = if (agentType.isolation == "worktree") {
            createWorktree(project.basePath)
        } else null
        if (workTreePath != null) {
            childLoop.workTreePath = workTreePath
        }

        // 子 Agent 的审批策略：autoApprove=true → 所有工具免审批执行。
        // 设计意图：子 Agent 在隔离任务上下文中工作，由主 Agent 的 prompt 指令控制，
        // 免审批可避免用户被大量确认弹窗打扰。安全边界见 AgentType.kt 中的设计决策文档。
        childLoop.onConfirmTool = { toolName, _, latch, choice ->
            if (agentType.autoApprove) {
                choice.set(true)
            } else {
                choice.set(false)
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
            onProgress?.invoke(text)
        }

        val resultRef = AtomicReference<String>()
        val thinkingRef = AtomicReference<String>()
        // latch 等待子 Agent 完成回调，无需超时：
        // 1. 主 Agent 循环每轮 drainCompleted() 收结果，未完成前持续轮询
        // 2. 用户点停止 → AgentLoop.stop() → SubAgentRegistry.stopAll() 强制终止所有子 Agent
        // 3. Claude Code 同样不对子代理设超时（见 github.com/anthropics/claude-code/issues/61405）
        val latch = CountDownLatch(1)

        // Fork：从 params 读取父对话上下文（AgentLoop 注入的 JSON）
        val forkCtx: List<com.aiassistant.AnthropicMessage>? = if (agentType.fork) {
            try {
                val json = params["_forkHistory"]
                if (json != null) {
                    com.google.gson.Gson().fromJson(json, Array<com.aiassistant.AnthropicMessage>::class.java).toList()
                } else null
            } catch (_: Exception) { null }
        } else null

        return try {
            if (background) {
                val subId = "sub-${System.currentTimeMillis()}"
                val toolCallId = params["_toolCallId"]  // AgentLoop 注入，对齐 Claude Code
                com.aiassistant.agent.SubAgentRegistry.register(subId, description, childLoop, toolCallId)
                CompletableFuture.runAsync {
                    try {
                        childLoop.run(prompt, apiKey, forkHistory = forkCtx) { finalText, thinking ->
                            resultRef.set(finalText)
                            thinkingRef.set(thinking)
                            latch.countDown()
                        }
                        latch.await(30, java.util.concurrent.TimeUnit.MINUTES)
                        val result = resultRef.get()?.takeIf { it.isNotBlank() } ?: ""
                        if (result.isNotBlank()) {
                            com.aiassistant.agent.SubAgentRegistry.complete(subId, result)
                        } else {
                            com.aiassistant.agent.SubAgentRegistry.fail(subId, "未返回结果")
                        }
                    } catch (e: Exception) {
                        com.aiassistant.agent.SubAgentRegistry.fail(subId, e.message ?: "未知错误")
                    } finally {
                        childLoop.stop()
                        removeWorktree(workTreePath)
                    }
                }
                ToolResult.ok("子代理已启动: $description\n\nID: $subId | 类型: ${agentType.name} | 最大轮次: ${agentType.maxLoops}")
            } else {
                childLoop.run(prompt, apiKey, forkHistory = forkCtx) { finalText, thinking ->
                    resultRef.set(finalText)
                    thinkingRef.set(thinking)
                    latch.countDown()
                }

                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
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
                removeWorktree(workTreePath)
            }
        }
    }

    // ---- Worktree 隔离辅助 ----

    /** 创建 git worktree，返回路径；失败返回 null（降级为 in-process） */
    private fun createWorktree(basePath: String?): String? {
        if (basePath == null) return null
        val worktreeDir = java.io.File(basePath, ".claude/worktrees/subagent-${System.currentTimeMillis()}")
        val process = try {
            ProcessBuilder("git", "-C", basePath, "worktree", "add", worktreeDir.absolutePath)
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            com.aiassistant.AppLogger.warn("Worktree 创建异常: ${e.message}")
            return null
        }
        return try {
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                com.aiassistant.AppLogger.warn("Worktree 创建超时: git worktree add 未在 30s 内完成")
                return null
            }
            if (process.exitValue() == 0) {
                com.aiassistant.AppLogger.info("Worktree 创建成功: ${worktreeDir.absolutePath}")
                worktreeDir.absolutePath
            } else {
                com.aiassistant.AppLogger.warn("Worktree 创建失败: $output")
                null
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            com.aiassistant.AppLogger.warn("Worktree 创建异常: ${e.message}")
            null
        }
    }

    /** 删除 git worktree */
    private fun removeWorktree(path: String?) {
        if (path == null) return
        val worktreeDir = java.io.File(path)
        val basePath = worktreeDir.parentFile?.parentFile?.absolutePath ?: return
        val process = try {
            ProcessBuilder("git", "-C", basePath, "worktree", "remove", path, "--force")
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            com.aiassistant.AppLogger.warn("Worktree 删除异常: ${e.message}")
            return
        }
        try {
            val finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                com.aiassistant.AppLogger.warn("Worktree 删除超时: git worktree remove 未在 10s 内完成")
            } else {
                com.aiassistant.AppLogger.info("Worktree 已删除: $path")
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            com.aiassistant.AppLogger.warn("Worktree 删除异常: ${e.message}")
        }
    }
}
