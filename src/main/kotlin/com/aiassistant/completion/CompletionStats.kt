package com.aiassistant.completion

import com.google.gson.Gson
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地统计数据收集，支持按语言统计和持久化到 .claude/completion-stats.json。
 * 重启 IDE 后内存数据清零，持久化文件保留历史数据。
 */
object CompletionStats {

    private val gson = Gson()

    private val totalShown = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalAccepted = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalLatencyMs = java.util.concurrent.atomic.AtomicLong(0)

    private data class LangCounter(
        val shown: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
        val accepted: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0),
        val latencyMs: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0)
    )
    private val languageStats = ConcurrentHashMap<String, LangCounter>()

    /**
     * 记录一次补全显示，带语言标识和延迟。
     */
    fun recordShown(language: String, latencyMs: Long) {
        totalShown.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)
        val langCounter = languageStats.computeIfAbsent(language) { LangCounter() }
        langCounter.shown.incrementAndGet()
        langCounter.latencyMs.addAndGet(latencyMs)
    }

    /**
     * 记录一次补全接受，带语言标识。
     */
    fun recordAccepted(language: String) {
        totalAccepted.incrementAndGet()
        languageStats[language]?.accepted?.incrementAndGet()
    }

    // 兼容旧调用（无 language）
    fun recordShown(latencyMs: Long) = recordShown("unknown", latencyMs)
    fun recordAccepted() = recordAccepted("unknown")

    fun recordCancelled() {
        // 仅标记，不改变计数器
    }

    fun getShownCount(): Int = totalShown.get()
    fun getAcceptedCount(): Int = totalAccepted.get()

    data class LangSnapshot(val shown: Int, val accepted: Int, val avgLatencyMs: Long)

    data class StatsSnapshot(
        val shown: Int,
        val accepted: Int,
        val totalLatencyMs: Long,
        val byLanguage: Map<String, LangSnapshot>
    )

    @Synchronized
    fun getSnapshot(): StatsSnapshot {
        return StatsSnapshot(
            shown = totalShown.get(),
            accepted = totalAccepted.get(),
            totalLatencyMs = totalLatencyMs.get(),
            byLanguage = languageStats.mapValues { (_, v) ->
                val s = v.shown.get()
                LangSnapshot(s, v.accepted.get(), if (s > 0) v.latencyMs.get() / s else 0L)
            }
        )
    }

    fun getAcceptRate(): Double {
        val snap = getSnapshot()
        return if (snap.shown == 0) 0.0 else snap.accepted.toDouble() / snap.shown * 100.0
    }

    fun getAverageLatencyMs(): Long {
        val snap = getSnapshot()
        return if (snap.shown == 0) 0L else snap.totalLatencyMs / snap.shown
    }

    fun reset() {
        totalShown.set(0)
        totalAccepted.set(0)
        totalLatencyMs.set(0)
        languageStats.clear()
    }

    /**
     * 持久化当前统计数据到项目 .claude/completion-stats.json 文件。
     * 按天覆盖，每天保留最新快照。
     */
    fun persist(projectPath: String) {
        try {
            val snap = getSnapshot()
            val today = java.time.LocalDate.now().toString()
            val data = mapOf(
                "date" to today,
                "totalShown" to snap.shown,
                "totalAccepted" to snap.accepted,
                "averageLatencyMs" to getAverageLatencyMs(),
                "byLanguage" to snap.byLanguage.mapValues { (_, v) ->
                    mapOf("shown" to v.shown, "accepted" to v.accepted, "avgLatencyMs" to v.avgLatencyMs)
                }
            )
            val file = File("$projectPath/.claude/completion-stats.json")
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(data))
        } catch (_: Exception) {
            // 持久化失败静默忽略，不影响补全功能
        }
    }
}
