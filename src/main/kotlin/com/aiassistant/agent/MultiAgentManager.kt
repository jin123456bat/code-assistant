package com.aiassistant.agent

import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock

class MultiAgentManager(private val project: Project) {

    private val semaphore = Semaphore(3)
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun spawnAgent(task: String, parentSession: AgentSession): String {
        if (!semaphore.tryAcquire()) return "错误: Agent 并发数已达上限 (3)"

        return try {
            val subSession = AgentSession(title = "子任务: ${task.take(60)}")
            val subLoop = AgentLoop(project, subSession)
            val future = CompletableFuture.supplyAsync {
                subLoop.run(task)
            }
            val result = future.get()
            semaphore.release()

            when (result) {
                is AgentLoop.Result.Success -> {
                    parentSession.addMessage(
                        Message(
                            role = Role.TOOL_RESULT,
                            content = "子任务完成: ${result.text.take(400)}"
                        )
                    )
                    "子任务完成: ${result.text.take(500)}\n轮次: ${result.turns}"
                }

                is AgentLoop.Result.Error ->
                    "子任务失败: ${result.message}"
            }
        } catch (e: Exception) {
            semaphore.release()
            "子任务异常: ${e.message}"
        }
    }

    fun acquireFileLock(path: String): ReentrantLock =
        fileLocks.computeIfAbsent(path) { ReentrantLock() }

    fun getActiveCount(): Int = 3 - semaphore.availablePermits()
}
