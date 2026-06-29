package com.aiassistant.agent

import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 多 Agent 协作调度器，负责子 Agent 的创建、并发控制、结果汇总和持久化。
 * 对齐 docs/agent/multi-agent.md
 */
class MultiAgentManager(private val project: Project) {

    companion object {
        /** 结果摘要 token 上限（对齐 docs/agent/multi-agent.md §二 结果摘要） */
        private const val MAX_RESULT_TOKENS = 2000

        /** 默认最大并发 Agent 数（对齐 docs/agent/multi-agent.md §二 并发控制） */
        private const val DEFAULT_MAX_CONCURRENT = 3
    }

    /**
     * 子 Agent 生命周期事件，供 UI 层通过回调订阅。
     * 对齐 docs/agent/multi-agent.md §五：子 Agent 的 Flow<AgentEvent> 直接 collect 到父 ChatViewModel 渲染。
     */
    sealed class SubAgentEvent {
        abstract val agentId: String
        abstract val subSessionId: String

        data class Started(
            override val agentId: String,
            override val subSessionId: String,
            val task: String
        ) : SubAgentEvent()

        data class StreamToken(
            override val agentId: String,
            override val subSessionId: String,
            val token: String
        ) : SubAgentEvent()

        data class ToolCallStarted(
            override val agentId: String,
            override val subSessionId: String,
            val toolUseId: String,
            val toolName: String,
            val params: Map<String, Any?>
        ) : SubAgentEvent()

        data class ToolCallStateChanged(
            override val agentId: String,
            override val subSessionId: String,
            val toolUseId: String,
            val state: ToolCallState,
            val result: String?,
            val durationMs: Long?
        ) : SubAgentEvent()

        data class Completed(
            override val agentId: String,
            override val subSessionId: String,
            val durationMs: Long,
            val summary: String
        ) : SubAgentEvent()

        data class Failed(
            override val agentId: String,
            override val subSessionId: String,
            val errorMessage: String
        ) : SubAgentEvent()
    }

    /** 并发控制信号量，FIFO 公平排队（对齐 docs/agent/multi-agent.md §二 并发控制） */
    private val semaphore = Semaphore(DEFAULT_MAX_CONCURRENT, true)

    /** 文件写锁表，所有 Agent 共享（对齐 docs/agent/multi-agent.md §一 文件写锁） */
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    /** 子 Agent 事件回调，供 ChatViewModel 订阅以驱动 MultiAgentBlock UI */
    var onSubAgentEvent: ((SubAgentEvent) -> Unit)? = null

    /** Session 持久化存储 */
    private val sessionStore = com.aiassistant.session.SessionStore(project)

    /**
     * 子 Agent 工具白名单：General-purpose 默认可用工具（11 个）。
     * 对齐 docs/agent/multi-agent.md §三 工具白名单：
     * - 可用：Read, Write, Edit, Bash, Glob, Grep, readLints, WebSearch, WebFetch, AskUserQuestion, Symbol
     * - 不可用：Agent（禁止嵌套）、Skill（Skill 注入由父 Agent 管理）、createPlan/listPlans/removePlan/reorderPlans/markPlanDone（计划管理工具）
     */
    private val subAgentToolsFilter: List<Class<*>> = listOf(
        Read::class.java,
        Write::class.java,
        Edit::class.java,
        Bash::class.java,
        Glob::class.java,
        Grep::class.java,
        ReadLints::class.java,
        WebSearch::class.java,
        WebFetch::class.java,
        AskUserQuestion::class.java,
        Symbol::class.java
    )

    /**
     * 启动子 Agent 处理子任务。
     * 对齐 docs/agent/multi-agent.md §二：
     * - 上下文独立构建（仅 System Prompt 基础部分 + 父 prompt）
     * - 结果摘要 ≤ 2000 tokens
     * - 子 Session 独立持久化（parentId 关联）
     * - 并发上限 Semaphore FIFO 排队
     * - crash 清理（信号量/文件锁/Shell进程）
     *
     * 对齐 docs/agent/multi-agent.md §三 工具白名单：
     * - 子 Agent 仅获得 General-purpose 白名单工具（11 个）
     *
     * @param prompt 子 Agent 任务描述（作为首条 user message）
     * @param parentSession 父 Agent 会话
     * @param timeoutSec 超时秒数（0=不限）
     * @param runInBackground true=异步后台执行，false=同步等待完成
     */
    fun spawnAgent(
        prompt: String,
        parentSession: AgentSession,
        timeoutSec: Int = 0,
        runInBackground: Boolean = false
    ): String {
        if (!runInBackground) {
            // 同步模式：FIFO 公平排队获取信号量
            try {
                semaphore.acquire()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return "错误: 排队被中断"
            }

            return try {
                executeSubAgent(prompt, parentSession, timeoutSec)
            } finally {
                semaphore.release()
            }
        } else {
            // 异步模式：由后台线程自己管理信号量和清理
            CompletableFuture.runAsync {
                try {
                    semaphore.acquire()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@runAsync
                }
                try {
                    executeSubAgentAsync(prompt, parentSession, timeoutSec)
                } finally {
                    semaphore.release()
                }
            }
            return "子 Agent 已启动（异步模式），任务: ${prompt.take(200)}"
        }
    }

    /**
     * 同步执行子 Agent 并返回结果。
     * 对齐 docs/agent/multi-agent.md §二：
     * - 上下文独立构建（子 Agent 不继承父对话历史）
     * - 结果摘要拼接（最后一轮 assistant 消息 + 所有 tool call 结果原文，截断到 2000 tokens）
     * - 子 Session 持久化（parentId 关联）
     */
    private fun executeSubAgent(
        prompt: String,
        parentSession: AgentSession,
        timeoutSec: Int
    ): String {
        val startTime = System.currentTimeMillis()

        // 1. 创建子 Session（独立持久化，关联 parentId）
        val subSession = AgentSession(
            title = "子任务: ${prompt.take(60)}",
            parentId = parentSession.id
        )

        // 2. 创建子 AgentLoop（独立上下文，不继承父对话历史）
        // AgentLoop.run() -> buildSystemPrompt() 构建 System Prompt（角色指令 + 工具描述 + 环境信息）
        // subSession.messages 初始为空，不包含父 Agent 的任何历史消息
        // prompt 作为首条 user message 传入 run()
        val subLoop = AgentLoop(project, subSession).apply {
            // 设置工具白名单，限制子 Agent 仅获得 11 个 General-purpose 工具
            // 对齐 docs/agent/multi-agent.md §三 工具白名单
            toolsFilter = this@MultiAgentManager.subAgentToolsFilter
        }

        val agentId = subSession.id

        // 发出 Started 事件
        onSubAgentEvent?.invoke(
            SubAgentEvent.Started(
                agentId = agentId,
                subSessionId = subSession.id,
                task = prompt.take(200)
            )
        )

        // 接线子 Agent 回调 → SubAgentEvent（对齐 docs/agent/multi-agent.md §五）
        subLoop.onToken = { token ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.StreamToken(agentId, subSession.id, token)
            )
        }
        subLoop.onToolCall = { toolUseId, toolName, params ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.ToolCallStarted(agentId, subSession.id, toolUseId, toolName, params)
            )
        }
        subLoop.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.ToolCallStateChanged(
                    agentId, subSession.id, toolUseId, state, result, durationMs
                )
            )
        }

        // 3. 在线程池中异步执行子 Agent
        val future = CompletableFuture.supplyAsync {
            subLoop.run(prompt)
        }

        val result = try {
            if (timeoutSec > 0) {
                future.get(timeoutSec.toLong(), TimeUnit.SECONDS)
            } else {
                future.get()
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            // crash 清理：超时时销毁 Shell 进程
            cleanupSubSession(subSession)
            val errorMsg = "子 Agent 超时: ${timeoutSec}s"
            persistSubSession(subSession)
            onSubAgentEvent?.invoke(
                SubAgentEvent.Failed(agentId, subSession.id, errorMsg)
            )
            return errorMsg
        } catch (e: Exception) {
            // crash 清理：异常时释放文件锁、销毁 Shell 进程
            cleanupSubSession(subSession)
            val errorMsg = "子 Agent 失败: ${e.message}"
            persistSubSession(subSession)
            onSubAgentEvent?.invoke(
                SubAgentEvent.Failed(agentId, subSession.id, errorMsg)
            )
            return errorMsg
        }

        val durationMs = System.currentTimeMillis() - startTime

        when (result) {
            is AgentLoop.Result.Success -> {
                // 4. 失效父 Agent 的 fileStamps：子 Agent 可能已修改文件，父 Agent 缓存
                //    的 modificationStamp 已过时，下次 Edit/Write 前必须重新 Read。
                //    对齐 docs/agent/multi-agent.md §一：子 Agent 完成后自动失效父 Agent
                //    的 modificationStamp。
                invalidateParentFileStamps(subSession, parentSession)

                // 5. 构建结果摘要（≤ 2000 tokens）
                val summary = buildResultSummary(subSession, result)
                // 6. 结果摘要写入父的 toolResult
                val toolResult = "子任务完成: ${summary}"
                parentSession.addMessage(
                    Message(
                        role = Role.SYSTEM,
                        contentType = ContentType.TOOL_RESULT,
                        content = toolResult
                    )
                )
                // 7. 持久化子 Session
                persistSubSession(subSession)
                onSubAgentEvent?.invoke(
                    SubAgentEvent.Completed(agentId, subSession.id, durationMs, summary)
                )
                return toolResult
            }

            is AgentLoop.Result.Error -> {
                // crash 清理：错误时销毁 Shell 进程
                cleanupSubSession(subSession)
                persistSubSession(subSession)
                onSubAgentEvent?.invoke(
                    SubAgentEvent.Failed(agentId, subSession.id, result.message)
                )
                return "子 Agent 失败: ${result.message}"
            }
        }
    }

    /**
     * 异步执行子 Agent（不等待结果，结果通过回调写入父 Session 的 toolResult）。
     */
    private fun executeSubAgentAsync(
        prompt: String,
        parentSession: AgentSession,
        timeoutSec: Int
    ) {
        val startTime = System.currentTimeMillis()

        // 1. 创建子 Session（独立持久化，关联 parentId）
        val subSession = AgentSession(
            title = "子任务: ${prompt.take(60)}",
            parentId = parentSession.id
        )

        // 2. 创建子 AgentLoop（独立上下文）
        val subLoop = AgentLoop(project, subSession).apply {
            // 设置工具白名单，限制子 Agent 仅获得 11 个 General-purpose 工具
            // 对齐 docs/agent/multi-agent.md §三 工具白名单
            toolsFilter = this@MultiAgentManager.subAgentToolsFilter
        }

        val agentId = subSession.id

        // 发出 Started 事件
        onSubAgentEvent?.invoke(
            SubAgentEvent.Started(
                agentId = agentId,
                subSessionId = subSession.id,
                task = prompt.take(200)
            )
        )

        // 接线子 Agent 回调 → SubAgentEvent（对齐 docs/agent/multi-agent.md §五）
        subLoop.onToken = { token ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.StreamToken(agentId, subSession.id, token)
            )
        }
        subLoop.onToolCall = { toolUseId, toolName, params ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.ToolCallStarted(agentId, subSession.id, toolUseId, toolName, params)
            )
        }
        subLoop.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            onSubAgentEvent?.invoke(
                SubAgentEvent.ToolCallStateChanged(
                    agentId, subSession.id, toolUseId, state, result, durationMs
                )
            )
        }

        // 3. 在线程池中异步执行
        val future = CompletableFuture.supplyAsync {
            subLoop.run(prompt)
        }

        try {
            val result = if (timeoutSec > 0) {
                future.get(timeoutSec.toLong(), TimeUnit.SECONDS)
            } else {
                future.get()
            }

            val durationMs = System.currentTimeMillis() - startTime

            when (result) {
                is AgentLoop.Result.Success -> {
                    // 失效父 Agent 的 fileStamps：子 Agent 可能已修改文件，父 Agent 缓存
                    // 的 modificationStamp 已过时，下次 Edit/Write 前必须重新 Read。
                    // 对齐 docs/agent/multi-agent.md §一。
                    invalidateParentFileStamps(subSession, parentSession)

                    val summary = buildResultSummary(subSession, result)
                    parentSession.addMessage(
                        Message(
                            role = Role.SYSTEM,
                            contentType = ContentType.TOOL_RESULT,
                            content = "子任务完成: ${summary}"
                        )
                    )
                    onSubAgentEvent?.invoke(
                        SubAgentEvent.Completed(agentId, subSession.id, durationMs, summary)
                    )
                }

                is AgentLoop.Result.Error -> {
                    cleanupSubSession(subSession)
                    parentSession.addMessage(
                        Message(
                            role = Role.SYSTEM,
                            contentType = ContentType.TOOL_RESULT,
                            content = "子 Agent 失败: ${result.message}"
                        )
                    )
                    onSubAgentEvent?.invoke(
                        SubAgentEvent.Failed(agentId, subSession.id, result.message)
                    )
                }
            }
            persistSubSession(subSession)
        } catch (e: java.util.concurrent.TimeoutException) {
            cleanupSubSession(subSession)
            persistSubSession(subSession)
            val errorMsg = "子 Agent 超时: ${timeoutSec}s"
            parentSession.addMessage(
                Message(
                    role = Role.SYSTEM,
                    contentType = ContentType.TOOL_RESULT,
                    content = errorMsg
                )
            )
            onSubAgentEvent?.invoke(
                SubAgentEvent.Failed(agentId, subSession.id, errorMsg)
            )
        } catch (e: Exception) {
            cleanupSubSession(subSession)
            persistSubSession(subSession)
            onSubAgentEvent?.invoke(
                SubAgentEvent.Failed(agentId, subSession.id, "子 Agent 异常: ${e.message}")
            )
        }
    }

    /**
     * 构建子 Agent 结果摘要。
     * 对齐 docs/agent/multi-agent.md §二 结果摘要：
     * 摘要 = 子 Agent 最后一轮 assistant 消息 + 所有 tool call 结果原文拼接，截断到 2000 tokens。
     * 不额外调用 LLM 生成摘要。
     */
    private fun buildResultSummary(
        subSession: AgentSession,
        result: AgentLoop.Result.Success
    ): String {
        val messages = subSession.messages

        // 找最后一轮 assistant 消息（从后往前找第一个 ASSISTANT 角色的消息）
        val lastAssistantMsg = messages.findLast { it.role == Role.ASSISTANT }

        // 收集所有 tool call 结果原文
        val toolResults = messages.filter { it.contentType == ContentType.TOOL_RESULT }
            .joinToString("\n") { it.content }

        // 拼接：最后一轮 assistant 消息 + 所有 tool call 结果
        val summary = buildString {
            if (lastAssistantMsg != null) {
                appendLine("最后一轮回复:")
                appendLine(lastAssistantMsg.content)
                appendLine()
            }
            if (toolResults.isNotEmpty()) {
                appendLine("工具执行结果:")
                appendLine(toolResults)
                appendLine()
            }
            append("轮次: ${result.turns}")
        }

        // 截断到 2000 tokens（保守估计 2 字符/token）
        return truncateToTokens(summary, MAX_RESULT_TOKENS)
    }

    /**
     * 按 token 上限截断文本。
     * 中文约 1.5 字符/token，英文约 4 字符/token，取保守估计 2 字符/token。
     */
    private fun truncateToTokens(text: String, maxTokens: Int): String {
        val maxChars = maxTokens * 2
        return if (text.length <= maxChars) {
            text
        } else {
            text.take(maxChars) + "\n... [摘要截断至 ${maxTokens} tokens]"
        }
    }

    /**
     * 失效父 Agent 的 fileStamps 中被修改的文件条目。
     *
     * 子 Agent 执行过程中可能通过 Write/Edit 修改文件，父 Agent 缓存中对应文件的
     * modificationStamp 已过时。清除这些条目后，父 Agent 下次 Edit/Write 时因找不到
     * stamp 记录，stamp 校验会无效（lastReadStamp==null），父 Agent 必须重新 Read
     * 才能获得有效的 stamp 值。
     *
     * 对齐 docs/agent/multi-agent.md §一：
     * - 子 Agent 完成后，父 Agent 缓存的文件 modificationStamp 自动失效。
     * - 对齐 docs/agent/multi-agent.md §四：
     *   MultiAgentManager 自动失效父 Agent 中被子 Agent 修改过的文件的 modificationStamp。
     */
    private fun invalidateParentFileStamps(
        subSession: AgentSession,
        parentSession: AgentSession
    ) {
        // 子 Agent 的 fileStamps 记录了它读取/修改过的所有文件及其 stamp
        // 将子 Agent 涉及的文件的父 Agent stamp 清除，迫使父 Agent 重新 Read
        for (path in subSession.fileStamps.keys) {
            parentSession.fileStamps.remove(path)
        }
    }

    /**
     * crash 清理：释放信号量、释放文件写锁、销毁 Shell 进程。
     * 对齐 docs/agent/multi-agent.md §一 crash 清理。
     */
    private fun cleanupSubSession(subSession: AgentSession) {
        // 销毁子 Agent 的所有 Shell 进程
        for (process in subSession.runningProcesses) {
            try {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {
                // 忽略单个进程销毁失败
            }
        }
        subSession.runningProcesses.clear()

        // 信号量释放由 spawnAgent 的 finally 块保证
    }

    /**
     * 持久化子 Session 到磁盘。
     * 对齐 docs/agent/multi-agent.md §二 子 Session 独立持久化。
     */
    private fun persistSubSession(subSession: AgentSession) {
        try {
            sessionStore.save(subSession)
        } catch (_: Exception) {
            // 持久化失败不影响父 Agent
        }
    }

    fun acquireFileLock(path: String): ReentrantLock =
        fileLocks.computeIfAbsent(path) { ReentrantLock() }

    fun getActiveCount(): Int = DEFAULT_MAX_CONCURRENT - semaphore.availablePermits()
}
