package com.aiassistant.completion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 防抖管理器。字符/回车/关键字/空格/小粘贴 → debounce 后触发；手动快捷键 → 立即触发。
 * 新输入 → 取消进行中请求并重新计时。
 */
class CompletionDebounceManager(
    private val debounceMs: Long,
    private val onTrigger: () -> Unit,
    private val onCancelPending: () -> Unit
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var pendingFuture: ScheduledFuture<*>? = null

    @Synchronized
    fun onUserInput() {
        cancelPending()
        scheduleDebounce()
    }

    @Synchronized
    fun onManualTrigger() {
        cancelPending()
        onCancelPending()
        onTrigger()
    }

    private fun scheduleDebounce() {
        pendingFuture = scheduler.schedule({
            synchronized(this) {
                onTrigger()
            }
        }, debounceMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun cancelPending() {
        pendingFuture?.cancel(false)
        pendingFuture = null
        // 不再调用 onCancelPending()，由调用方按场景自行调用
    }

    fun dispose() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (_: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
}
