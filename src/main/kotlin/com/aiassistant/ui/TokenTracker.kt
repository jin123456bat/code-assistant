package com.aiassistant.ui

import com.aiassistant.AiAssistantBundle
import com.aiassistant.agent.AgentContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Token 用量聚合统计，支持日/周维度 + 总览 */
object TokenTracker {

    data class DailySummary(
        val date: String, val inputTokens: Long, val outputTokens: Long,
        val rounds: Int
    )

    fun getDailyStats(stats: AgentContext.TokenStats): List<DailySummary> {
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.ofPattern("MM-dd")
        return stats.perRound
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .map { (date, rounds) ->
                DailySummary(date.format(fmt), rounds.sumOf { it.inputTokens.toLong() },
                    rounds.sumOf { it.outputTokens.toLong() }, rounds.size)
            }
            .sortedByDescending { it.date }
    }

    fun getWeeklyStats(stats: AgentContext.TokenStats): List<DailySummary> {
        val zone = ZoneId.systemDefault()
        return stats.perRound
            .groupBy {
                val date = Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
                date.with(java.time.DayOfWeek.MONDAY).format(DateTimeFormatter.ofPattern("MM-dd"))
            }
            .map { (week, rounds) ->
                DailySummary(week, rounds.sumOf { it.inputTokens.toLong() },
                    rounds.sumOf { it.outputTokens.toLong() }, rounds.size)
            }
            .sortedByDescending { it.date }
    }

    fun getTotalStats(stats: AgentContext.TokenStats): String = buildString {
        append(AiAssistantBundle.message("token.total.input", fmt(stats.totalInput)))
        append(AiAssistantBundle.message("token.total.output", fmt(stats.totalOutput)))
        append(AiAssistantBundle.message("token.rounds", stats.roundCount))
        append(AiAssistantBundle.message("token.cost", "%.4f".format((stats.totalInput * 0.14 + stats.totalOutput * 1.10) / 1_000_000)))
    }

    private fun fmt(n: Long) = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1000 -> "${n / 1000}k"
        else -> "$n"
    }
}
