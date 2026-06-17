package com.aiassistant.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 并行子 Agent 注册中心 — 管理后台子 Agent 的生命周期和结果。
 * 线程安全，支持多子 Agent 并发执行。
 */
object SubAgentRegistry {

    enum class Status { RUNNING, DONE, FAILED }

    data class Entry(
        val id: String,
        val description: String,
        val status: Status,
        val result: String? = null,
        val error: String? = null,
        val startTime: Long = System.currentTimeMillis()
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    // 运行中的子 Agent 实例引用（用于 stopAll 停止）
    private val loops = ConcurrentHashMap<String, AgentLoop>()
    // drainLock 消除 complete/fail 与 drainCompleted 之间的弱一致性窗口
    private val drainLock = Any()

    fun register(id: String, description: String, loop: AgentLoop? = null): Entry {
        val entry = Entry(id, description, Status.RUNNING)
        entries[id] = entry
        if (loop != null) loops[id] = loop
        return entry
    }

    /** 停止所有运行中的子 Agent */
    fun stopAll() {
        loops.values.forEach { it.stop() }
        loops.clear()
        // 标记所有 RUNNING 条目为 FAILED，防止内存泄漏
        entries.forEach { (id, entry) ->
            if (entry.status == Status.RUNNING) {
                entries[id] = entry.copy(status = Status.FAILED, error = "主对话停止")
            }
        }
    }

    fun complete(id: String, result: String) {
        synchronized(drainLock) {
            entries[id]?.let {
                entries[id] = it.copy(status = Status.DONE, result = result)
            }
        }
        loops.remove(id)
    }

    fun fail(id: String, error: String) {
        synchronized(drainLock) {
            entries[id]?.let {
                entries[id] = it.copy(status = Status.FAILED, error = error)
            }
        }
        loops.remove(id)
    }

    fun get(id: String): Entry? = entries[id]

    /** 获取所有已完成但尚未消费的结果，消费后移除。与 complete/fail 共享锁消除弱一致性窗口。 */
    fun drainCompleted(): List<Entry> {
        val completed = mutableListOf<Entry>()
        synchronized(drainLock) {
            val iter = entries.entries.iterator()
            while (iter.hasNext()) {
                val (_, entry) = iter.next()
                if (entry.status != Status.RUNNING) {
                    completed.add(entry)
                    iter.remove()
                }
            }
        }
        return completed
    }

    fun isRunning(id: String): Boolean = entries[id]?.status == Status.RUNNING

    fun runningCount(): Int = entries.values.count { it.status == Status.RUNNING }
}
