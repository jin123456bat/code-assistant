package com.aiassistant.completion

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地统计数据收集，存内存，不上报。重启 IDE 清零。
 */
object CompletionStats {

    private val totalShown = AtomicInteger(0)
    private val totalAccepted = AtomicInteger(0)
    private val totalLatencyMs = AtomicLong(0)

    fun recordShown(latencyMs: Long) {
        totalShown.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)
    }

    fun recordAccepted() {
        totalAccepted.incrementAndGet()
    }

    fun recordCancelled() {
        // 仅标记，不改变计数器
    }

    fun getShownCount(): Int = totalShown.get()
    fun getAcceptedCount(): Int = totalAccepted.get()

    data class StatsSnapshot(
        val shown: Int,
        val accepted: Int,
        val totalLatencyMs: Long
    )

    @Synchronized
    fun getSnapshot(): StatsSnapshot = StatsSnapshot(
        shown = totalShown.get(),
        accepted = totalAccepted.get(),
        totalLatencyMs = totalLatencyMs.get()
    )

    fun getAcceptRate(): Double {
        val snap = getSnapshot()
        if (snap.shown == 0) return 0.0
        return snap.accepted.toDouble() / snap.shown * 100.0
    }

    fun getAverageLatencyMs(): Long {
        val snap = getSnapshot()
        if (snap.shown == 0) return 0L
        return snap.totalLatencyMs / snap.shown
    }

    fun reset() {
        totalShown.set(0)
        totalAccepted.set(0)
        totalLatencyMs.set(0)
    }
}
