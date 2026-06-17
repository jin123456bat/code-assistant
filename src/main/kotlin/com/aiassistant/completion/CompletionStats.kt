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

    fun getAcceptRate(): Double {
        val shown = totalShown.get()
        if (shown == 0) return 0.0
        return totalAccepted.get().toDouble() / shown * 100.0
    }

    fun getAverageLatencyMs(): Long {
        val shown = totalShown.get()
        if (shown == 0) return 0L
        return totalLatencyMs.get() / shown
    }

    fun reset() {
        totalShown.set(0)
        totalAccepted.set(0)
        totalLatencyMs.set(0)
    }
}
