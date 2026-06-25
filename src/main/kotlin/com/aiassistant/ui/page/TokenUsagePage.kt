package com.aiassistant.ui.page

import com.aiassistant.session.SessionStore
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.time.YearMonth
import java.time.ZoneId
import javax.swing.*

class TokenUsagePage(project: Project) : JPanel(BorderLayout()) {

    private val store = SessionStore(project)
    private val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); border =
        BorderFactory.createEmptyBorder(8, 0, 8, 0)
    }

    init {
        val rangeBox = JComboBox(arrayOf("本月", "今日", "总计")).apply {
            addActionListener { refreshView(store.listAll(), (selectedItem as String)) }
        }
        val headerPanel = JPanel(BorderLayout())
        headerPanel.add(
            JLabel("<html><b style='font-size:16px'>📊 Token 消耗</b></html>"),
            BorderLayout.WEST
        )
        headerPanel.add(rangeBox, BorderLayout.EAST)
        add(headerPanel, BorderLayout.NORTH)
        add(JScrollPane(body).apply { border = null }, BorderLayout.CENTER)
        refreshView(store.listAll(), "本月")
    }

    private fun refreshView(sessions: List<com.aiassistant.session.SessionIndex>, range: String) {
        body.removeAll()
        val filtered = when (range) {
            "今日" -> sessions.filter { it.updatedAt.toEpochMilli() > System.currentTimeMillis() - 86400000 }
            "本月" -> {
                val monthStart = YearMonth.now(ZoneId.systemDefault()).atDay(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()
                sessions.filter { !it.updatedAt.isBefore(monthStart) }
            }

            else -> sessions
        }
        val total = filtered.sumOf { it.totalTokens }
        val cost = String.format("%.2f", total * 0.000007)
        body.add(JLabel("总消耗: ${total / 1000}K tokens · ¥$cost"))
        body.add(Box.createVerticalStrut(12))
        val sparkline = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                g.color = JBColor(0x3B82F6, 0x60A5FA)
                val data = filtered.takeLast(30).map { it.totalTokens.toFloat() }
                if (data.isEmpty()) return;
                val max = data.max(); if (max == 0f) return
                val w = width.toFloat() / data.size
                data.forEachIndexed { i, v ->
                    val h = (v / max * (height - 4)).coerceAtLeast(2f)
                    g.fillRect(
                        (i * w).toInt(),
                        (height - h).toInt(),
                        (w - 1).toInt().coerceAtLeast(1),
                        h.toInt()
                    )
                }
            }
        }
        sparkline.preferredSize = Dimension(600, 80)
        sparkline.border = BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x374151))
        sparkline.toolTipText = "hover 显示具体数值"
        body.add(sparkline)
        body.add(Box.createVerticalStrut(8))
        body.add(JLabel("<html><span style='color:#6B7280;font-size:10px'>1日${" ".repeat(50)}最近30天</span></html>"))
        body.add(Box.createVerticalStrut(12))
        body.add(JLabel("<html><b>按会话</b></html>"))
        filtered.sortedByDescending { it.updatedAt }.take(10).forEach { s ->
            body.add(
                JLabel(
                    "<html>📝 ${s.title} &nbsp;<span style='color:#6B7280;font-size:11px'>${s.totalTokens / 1000}K · ¥${
                        String.format(
                            "%.2f",
                            s.totalTokens * 0.000007
                        )
                    }</span></html>"
                )
            )
        }
        body.add(JLabel("<html><span style='color:#6B7280;font-size:11px'>模型: deepseek-v4-pro（固定）</span></html>"))
        body.revalidate(); body.repaint()
    }
}
