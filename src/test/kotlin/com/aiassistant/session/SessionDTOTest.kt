package com.aiassistant.session

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SessionDTO 及相关数据类单元测试。
 * 验证持久化 DTO 的构造、默认值和关键行为。
 */
class SessionDTOTest {

    private val now = Instant.now()
    private val today = LocalDate.now()

    // ═══ SessionDTO ═══

    @Test
    fun `SessionDTO 最小构造`() {
        val dto = SessionDTO(
            id = "s1",
            title = "测试会话",
            createdAt = now,
            updatedAt = now,
            messages = emptyList()
        )
        assertEquals("s1", dto.id)
        assertEquals("测试会话", dto.title)
        assertNull(dto.parentId)
        assertEquals(0, dto.compactCount)
        assertEquals(0, dto.errorCount)
        assertEquals(emptyList(), dto.approvedTools)
        assertEquals(emptyList(), dto.calledSkills)
        assertEquals(emptyList(), dto.firstToolUseDone)
    }

    @Test
    fun `SessionDTO 完整构造含所有字段`() {
        val dto = SessionDTO(
            id = "s2",
            parentId = "parent-1",
            title = "子会话",
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
            plan = null,
            totalTokens = TotalTokensDTO(inputTokens = 5000, outputTokens = 2000),
            compactSummary = "上下文摘要内容",
            compactCount = 2,
            approvedTools = listOf("Read", "Write"),
            approvedMcpServers = listOf("github"),
            state = "running",
            parentTotalTokens = 15000L,
            errorCount = 1,
            calledSkills = listOf("/review"),
            firstToolUseDone = listOf("Bash", "Read")
        )
        assertEquals("parent-1", dto.parentId)
        assertEquals(5000, dto.totalTokens?.inputTokens)
        assertEquals("上下文摘要内容", dto.compactSummary)
        assertEquals(2, dto.compactCount)
        assertEquals(2, dto.approvedTools.size)
        assertEquals("github", dto.approvedMcpServers.first())
        assertEquals("running", dto.state)
        assertEquals(15000L, dto.parentTotalTokens)
        assertEquals(1, dto.errorCount)
        assertTrue(dto.calledSkills.contains("/review"))
        assertTrue(dto.firstToolUseDone.contains("Bash"))
    }

    // ═══ MessageDTO ═══

    @Test
    fun `MessageDTO 默认值正确`() {
        val msg = MessageDTO(
            id = "m1",
            role = "user",
            content = "你好",
            timestamp = now
        )
        assertEquals("m1", msg.id)
        assertEquals("user", msg.role)
        assertNull(msg.contentType)
        assertFalse(msg.deleted)
        assertNull(msg.toolCalls)
        assertNull(msg.tokenUsage)
    }

    @Test
    fun `MessageDTO 包含 toolCalls 和 tokenUsage`() {
        val msg = MessageDTO(
            id = "m2",
            role = "assistant",
            content = "我来读取文件",
            contentType = "text",
            timestamp = now,
            toolCalls = listOf(
                ToolCallDTO(
                    id = "tc1",
                    name = "Read",
                    parameters = mapOf("filePath" to "src/main/User.kt"),
                    state = "completed",
                    durationMs = 150L
                )
            ),
            tokenUsage = TokenUsageDTO(inputTokens = 100, outputTokens = 50)
        )
        assertEquals("assistant", msg.role)
        assertEquals(1, msg.toolCalls!!.size)
        assertEquals("Read", msg.toolCalls!![0].name)
        assertEquals("completed", msg.toolCalls!![0].state)
        assertEquals(150L, msg.toolCalls!![0].durationMs)
        assertEquals(100, msg.tokenUsage!!.inputTokens)
        assertEquals(50, msg.tokenUsage!!.outputTokens)
    }

    // ═══ ToolCallDTO ═══

    @Test
    fun `ToolCallDTO 默认值正确`() {
        val tc = ToolCallDTO(
            id = "tc1",
            name = "Bash",
            parameters = emptyMap(),
            state = "pending"
        )
        assertEquals("pending", tc.state)
        assertNull(tc.result)
        assertNull(tc.durationMs)
        assertEquals(0, tc.parameters.size)
    }

    @Test
    fun `ToolCallDTO 含结果和耗时`() {
        val tc = ToolCallDTO(
            id = "tc2",
            name = "Write",
            parameters = mapOf("filePath" to "test.kt", "content" to "data"),
            result = "文件写入成功",
            state = "completed",
            durationMs = 200L
        )
        assertEquals("completed", tc.state)
        assertEquals("文件写入成功", tc.result)
        assertEquals(200L, tc.durationMs)
        assertEquals(2, tc.parameters.size)
    }

    // ═══ PlanDTO ═══

    @Test
    fun `PlanDTO 基本构造`() {
        val plan = PlanDTO(
            id = "p1",
            status = "RUNNING",
            summary = "实现用户认证",
            currentPlanIndex = 0,
            plans = listOf(
                PlanItemDTO(
                    id = "pi1",
                    description = "创建 User 实体",
                    tool = "Write",
                    files = listOf("User.kt"),
                    status = "PENDING",
                    result = null
                )
            ),
            createdAt = now,
            updatedAt = now
        )
        assertEquals("RUNNING", plan.status)
        assertEquals(0, plan.currentPlanIndex)
        assertEquals(1, plan.plans.size)
        assertEquals("PENDING", plan.plans[0].status)
    }

    @Test
    fun `PlanItemDTO retryCount 默认值`() {
        val item = PlanItemDTO(
            id = "pi1",
            description = "测试步骤",
            tool = "Bash",
            files = listOf("test.kt"),
            status = "PAUSED",
            result = "失败"
        )
        assertEquals(0, item.retryCount, "默认重试次数应为 0")
    }

    @Test
    fun `PlanItemDTO 含重试次数`() {
        val item = PlanItemDTO(
            id = "pi2",
            description = "重试步骤",
            tool = "Bash",
            files = listOf("build.sh"),
            status = "PAUSED",
            result = "第2次失败",
            retryCount = 2
        )
        assertEquals(2, item.retryCount)
    }

    // ═══ SessionIndexDTO ═══

    @Test
    fun `SessionIndexDTO 默认值正确`() {
        val idx = SessionIndexDTO(
            id = "s1",
            title = "会话",
            createdAt = now,
            updatedAt = now,
            messageCount = 5,
            totalTokens = 1000
        )
        assertEquals("s1", idx.id)
        assertEquals(5, idx.messageCount)
        assertEquals(1000, idx.totalTokens)
        assertEquals(0, idx.toolCallCount)
        assertFalse(idx.hasActivePlan)
        assertFalse(idx.corrupted)
        assertFalse(idx.deleted)
    }

    @Test
    fun `SessionIndexDTO 损坏标记`() {
        val idx = SessionIndexDTO(
            id = "s1",
            title = "损坏会话",
            createdAt = now,
            updatedAt = now,
            messageCount = 0,
            totalTokens = 0,
            corrupted = true,
            deleted = true
        )
        assertTrue(idx.corrupted)
        assertTrue(idx.deleted)
    }

    // ═══ SessionIndex ═══

    @Test
    fun `SessionIndex 等价于 SessionIndexDTO 字段`() {
        val idx = SessionIndex(
            id = "s1",
            title = "会话",
            createdAt = now,
            updatedAt = now,
            messageCount = 10,
            totalTokens = 5000,
            toolCallCount = 3,
            hasActivePlan = true,
            parentId = "parent-1",
            parentTotalTokens = 25000L
        )
        assertEquals("s1", idx.id)
        assertEquals(10, idx.messageCount)
        assertEquals(5000, idx.totalTokens)
        assertEquals(3, idx.toolCallCount)
        assertTrue(idx.hasActivePlan)
        assertEquals("parent-1", idx.parentId)
        assertEquals(25000L, idx.parentTotalTokens)
        assertFalse(idx.corrupted)
        assertFalse(idx.deleted)
    }

    // ═══ Token 聚合数据类 ═══

    @Test
    fun `TokenAggregation 构建正确`() {
        val periods = listOf(
            TokenPeriod(today, inputTokens = 1000, outputTokens = 500),
            TokenPeriod(today.minusDays(1), inputTokens = 2000, outputTokens = 800)
        )
        val agg = TokenAggregation(
            periods = periods,
            grandTotal = 4300,
            estimatedCost = BigDecimal("0.05")
        )
        assertEquals(2, agg.periods.size)
        assertEquals(4300, agg.grandTotal)
        assertEquals(BigDecimal("0.05"), agg.estimatedCost)
    }

    @Test
    fun `TokenPeriod 字段正确`() {
        val period = TokenPeriod(
            date = today,
            inputTokens = 1500,
            outputTokens = 600
        )
        assertEquals(today, period.date)
        assertEquals(1500, period.inputTokens)
        assertEquals(600, period.outputTokens)
    }

    // ═══ SessionExportDTO ═══

    @Test
    fun `SessionExportDTO 构建正确`() {
        val export = SessionExportDTO(
            exportedAt = now,
            sessionCount = 3,
            sessions = listOf(
                SessionDTO(
                    "s1",
                    title = "A",
                    createdAt = now,
                    updatedAt = now,
                    messages = emptyList()
                ),
                SessionDTO(
                    "s2",
                    title = "B",
                    createdAt = now,
                    updatedAt = now,
                    messages = emptyList()
                ),
                SessionDTO(
                    "s3",
                    title = "C",
                    createdAt = now,
                    updatedAt = now,
                    messages = emptyList()
                )
            )
        )
        assertEquals(3, export.sessionCount)
        assertEquals(3, export.sessions.size)
    }
}
