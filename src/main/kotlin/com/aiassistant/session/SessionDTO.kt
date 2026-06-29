package com.aiassistant.session

import java.time.Instant

// ══════════════════════════════════════════════════════════════════
// 持久化 DTO（对齐 docs/specs/persistence.md §6.1 Session JSON）
// ══════════════════════════════════════════════════════════════════

data class SessionDTO(
    val id: String,
    val parentId: String? = null,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<MessageDTO>,
    val plan: PlanDTO? = null,
    val totalTokens: TotalTokensDTO? = null,
    val compactSummary: String? = null,
    val compactCount: Int = 0,
    val approvedTools: List<String> = emptyList(),
    val approvedMcpServers: List<String> = emptyList(),
    val state: String? = null,
    /** 聚合所有子 session 的 totalTokens，仅父 session 有值（对齐 docs/agent/session.md §六） */
    val parentTotalTokens: Long? = null,
    /** 累计错误次数，用于连续错误升级规则（对齐 docs/agent/loop.md §三） */
    val errorCount: Int = 0,
    /** 本会话中已被调用过的 Skill 名称集合（对齐 docs/agent/skills.md §五） */
    val calledSkills: List<String> = emptyList(),
    /** 本会话中已完成首次审批的工具名集合，持久化信任关系（对齐 docs/agent/tools.md §六 首次工具使用） */
    val firstToolUseDone: List<String> = emptyList()
)

data class SessionExportDTO(
    val exportedAt: Instant,
    val sessionCount: Int,
    val sessions: List<SessionDTO>
)

/**
 * 对齐 docs/specs/persistence.md §6.1 Plan JSON Schema
 *
 * 存于 Session JSON 的 plan 字段：
 * - id: 计划唯一标识
 * - status: 见 PlanStatus 状态
 * - summary: 一句话描述
 * - currentPlanIndex: 当前执行到第几项（0-based）
 * - plans: 计划项列表
 * - createdAt: 创建时间（ISO 8601）
 * - updatedAt: 更新时间（ISO 8601）
 */
data class PlanDTO(
    val id: String,
    val status: String,
    val summary: String,
    val currentPlanIndex: Int = 0,
    val plans: List<PlanItemDTO>,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * 对齐 docs/specs/persistence.md §6.1 Plan 子项字段说明
 *
 * Plan 子项字段：
 * - id: 计划项唯一标识
 * - description: 计划项描述
 * - tool: 建议工具名
 * - files: 涉及文件（含行号如 "UserService.kt:40-60"）
 * - status: 见 PlanStatus 状态
 * - result: 执行结果
 * - retryCount: 重试次数
 */
data class PlanItemDTO(
    val id: String, val description: String, val tool: String,
    val files: List<String>, val status: String, val result: String?,
    val retryCount: Int = 0
)

/**
 * 对齐 docs/specs/persistence.md §6.1 Message 字段说明
 */
data class MessageDTO(
    val id: String,
    val role: String,
    val content: String,
    val contentType: String? = null,
    val timestamp: Instant,
    val deleted: Boolean = false,
    val feedback: String? = null,
    val toolCalls: List<ToolCallDTO>? = null,
    val tokenUsage: TokenUsageDTO? = null
)

data class ToolCallDTO(
    val id: String, val name: String,
    val parameters: Map<String, Any?> = emptyMap(),
    val result: String? = null,
    val state: String,
    val durationMs: Long? = null
)

/**
 * 单条消息的 token 用量（对齐 docs/specs/persistence.md §6.1 tokenUsage）
 */
data class TokenUsageDTO(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val timestamp: Instant = Instant.now()
)

/**
 * 会话级 token 累计（对齐 docs/specs/persistence.md §6.1 totalTokens）
 */
data class TotalTokensDTO(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0
)

/**
 * 对齐 docs/specs/persistence.md §6.2 Session Index
 */
data class SessionIndexDTO(
    val id: String, val title: String,
    val createdAt: Instant, val updatedAt: Instant,
    val messageCount: Int, val totalTokens: Long,
    val toolCallCount: Int = 0, val hasActivePlan: Boolean = false,
    val parentId: String? = null,
    val parentTotalTokens: Long? = null,
    /** Session JSON 文件损坏标记（对齐 docs/agent/session.md §一 "损坏文件用户感知"） */
    val corrupted: Boolean = false
)

// ══════════════════════════════════════════════════════════════════
// 公共数据类
// ══════════════════════════════════════════════════════════════════

data class SessionIndex(
    val id: String, val title: String, val createdAt: Instant, val updatedAt: Instant,
    val messageCount: Int, val totalTokens: Long,
    val toolCallCount: Int = 0, val hasActivePlan: Boolean = false,
    val parentId: String? = null,
    val parentTotalTokens: Long? = null,
    /** Session JSON 文件损坏标记（对齐 docs/agent/session.md §一 "损坏文件用户感知"） */
    val corrupted: Boolean = false
)

// ══════════════════════════════════════════════════════════════════
// Token 聚合数据类（对齐 docs/agent/session.md §六）
// ══════════════════════════════════════════════════════════════════

data class TokenAggregation(
    val periods: List<TokenPeriod>,
    val grandTotal: Long,
    val estimatedCost: java.math.BigDecimal
)

data class TokenPeriod(
    val date: java.time.LocalDate,
    val inputTokens: Long,
    val outputTokens: Long
)
