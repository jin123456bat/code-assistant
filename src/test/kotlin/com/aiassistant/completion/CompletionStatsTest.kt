package com.aiassistant.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CompletionStats 单元测试。
 * 验证统计记录、快照、接受率和重置等功能。
 *
 * 注意：每个测试前调用 reset() 确保隔离。
 */
class CompletionStatsTest {

    private fun setup() {
        CompletionStats.reset()
    }

    // ═══ 计数测试 ═══

    @Test
    fun `初始状态各计数为 0`() {
        setup()
        assertEquals(0, CompletionStats.getShownCount())
        assertEquals(0, CompletionStats.getAcceptedCount())
        assertEquals(0, CompletionStats.getRejectedCount())
    }

    @Test
    fun `recordShown 增加显示计数`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordShown("kotlin", 150L)
        assertEquals(2, CompletionStats.getShownCount())
    }

    @Test
    fun `recordAccepted 增加接受计数`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordAccepted("kotlin")
        CompletionStats.recordAccepted("kotlin")
        assertEquals(2, CompletionStats.getAcceptedCount())
    }

    @Test
    fun `recordRejected 增加拒绝计数`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordRejected("kotlin")
        assertEquals(1, CompletionStats.getRejectedCount())
    }

    // ═══ 接受率测试 ═══

    @Test
    fun `无显示时接受率为 0`() {
        setup()
        assertEquals(0.0, CompletionStats.getAcceptRate())
    }

    @Test
    fun `50 percent 接受率计算正确`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordShown("kotlin", 200L)
        CompletionStats.recordAccepted("kotlin")
        assertEquals(50.0, CompletionStats.getAcceptRate())
    }

    @Test
    fun `100 percent 接受率计算正确`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordShown("kotlin", 200L)
        CompletionStats.recordAccepted("kotlin")
        CompletionStats.recordAccepted("kotlin")
        assertEquals(100.0, CompletionStats.getAcceptRate())
    }

    // ═══ 延迟测试 ═══

    @Test
    fun `平均延迟计算正确`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordShown("kotlin", 300L)
        assertEquals(200L, CompletionStats.getAverageLatencyMs())
    }

    @Test
    fun `无显示时平均延迟为 0`() {
        setup()
        assertEquals(0L, CompletionStats.getAverageLatencyMs())
    }

    // ═══ 快照测试 ═══

    @Test
    fun `快照包含所有统计维度`() {
        setup()
        CompletionStats.recordShown("kotlin", 150L)
        CompletionStats.recordShown("java", 200L)
        CompletionStats.recordAccepted("kotlin")
        CompletionStats.recordRejected("java")

        val snap = CompletionStats.getSnapshot()
        assertEquals(2, snap.shown)
        assertEquals(1, snap.accepted)
        assertEquals(1, snap.rejected)
        assertTrue(snap.byLanguage.size >= 1, "快照应包含语言维度数据")
    }

    @Test
    fun `语言快照统计独立`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordShown("kotlin", 200L)
        CompletionStats.recordAccepted("kotlin")

        CompletionStats.recordShown("java", 50L)
        CompletionStats.recordRejected("java")

        val snap = CompletionStats.getSnapshot()
        val kotlinStat = snap.byLanguage["kotlin"]
        assertNotNull(kotlinStat, "应有 kotlin 统计")
        assertEquals(2, kotlinStat.shown)
        assertEquals(1, kotlinStat.accepted)
        assertEquals(0, kotlinStat.rejected)

        val javaStat = snap.byLanguage["java"]
        assertNotNull(javaStat, "应有 java 统计")
        assertEquals(1, javaStat.shown)
        assertEquals(1, javaStat.rejected)
    }

    // ═══ Reset 测试 ═══

    @Test
    fun `reset 清空所有统计`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordAccepted("kotlin")
        CompletionStats.recordRejected("kotlin")

        CompletionStats.reset()

        assertEquals(0, CompletionStats.getShownCount())
        assertEquals(0, CompletionStats.getAcceptedCount())
        assertEquals(0, CompletionStats.getRejectedCount())
        assertEquals(0.0, CompletionStats.getAcceptRate())

        val snap = CompletionStats.getSnapshot()
        assertTrue(snap.byLanguage.isEmpty(), "reset 后语言统计应为空")
    }

    // ═══ 兼容旧调用（无语言参数）═══

    @Test
    fun `无语言参数的旧调用使用 unknown 作为语言`() {
        setup()
        CompletionStats.recordShown(150L)
        CompletionStats.recordAccepted()
        CompletionStats.recordRejected()

        assertEquals(1, CompletionStats.getShownCount())
        assertEquals(1, CompletionStats.getAcceptedCount())
        assertEquals(1, CompletionStats.getRejectedCount())

        val snap = CompletionStats.getSnapshot()
        assertNotNull(snap.byLanguage["unknown"], "旧调用应归类为 unknown")
    }

    // ═══ 边界情况 ═══

    @Test
    fun `零延迟不导致除零错误`() {
        setup()
        CompletionStats.recordShown("kotlin", 0L)
        assertEquals(0L, CompletionStats.getAverageLatencyMs())
    }

    @Test
    fun `大量记录不影响正确性`() {
        setup()
        repeat(1000) { i ->
            CompletionStats.recordShown("kotlin", 100L)
            if (i % 2 == 0) CompletionStats.recordAccepted("kotlin")
            else CompletionStats.recordRejected("kotlin")
        }
        assertEquals(1000, CompletionStats.getShownCount())
        assertEquals(500, CompletionStats.getAcceptedCount())
        assertEquals(500, CompletionStats.getRejectedCount())
        assertEquals(50.0, CompletionStats.getAcceptRate())
    }

    @Test
    fun `先 recordAccepted 再 recordShown 不丢统计`() {
        setup()
        // computeIfAbsent 确保无论调用顺序如何都不会丢统计
        CompletionStats.recordAccepted("python")
        CompletionStats.recordShown("python", 120L)
        CompletionStats.recordAccepted("python")

        assertEquals(1, CompletionStats.getShownCount())
        assertEquals(2, CompletionStats.getAcceptedCount())
    }

    // ═══ persist 测试 ═══

    @Test
    fun `persist 不抛出异常`() {
        setup()
        CompletionStats.recordShown("kotlin", 100L)
        CompletionStats.recordAccepted("kotlin")
        // persist 应静默处理任何 IO 异常，不抛到调用方
        try {
            CompletionStats.persist("/nonexistent/path/test")
        } catch (e: Exception) {
            // 不应该抛出异常
            assertTrue(false, "persist 不应抛出异常: ${e.message}")
        }
    }
}
