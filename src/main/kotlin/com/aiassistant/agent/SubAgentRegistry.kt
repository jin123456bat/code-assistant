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

    fun register(id: String, description: String): Entry {
        val entry = Entry(id, description, Status.RUNNING)
        entries[id] = entry
        return entry
    }

    fun complete(id: String, result: String) {
        entries[id]?.let {
            entries[id] = it.copy(status = Status.DONE, result = result)
        }
    }

    fun fail(id: String, error: String) {
        entries[id]?.let {
            entries[id] = it.copy(status = Status.FAILED, error = error)
        }
    }

    fun get(id: String): Entry? = entries[id]

    /** 获取所有已完成但尚未消费的结果，消费后移除 */
    fun drainCompleted(): List<Entry> {
        val completed = entries.values.filter { it.status != Status.RUNNING }.toList()
        completed.forEach { entries.remove(it.id) }
        return completed
    }

    fun isRunning(id: String): Boolean = entries[id]?.status == Status.RUNNING

    fun runningCount(): Int = entries.values.count { it.status == Status.RUNNING }
}
