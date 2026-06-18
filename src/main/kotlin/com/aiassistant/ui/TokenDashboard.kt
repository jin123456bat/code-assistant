package com.aiassistant.ui

import com.aiassistant.agent.AgentContext
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable

/** Token 用量 Dashboard：天/周表格 + 顶部总览，模態窗口 */
class TokenDashboard(
    private val stats: AgentContext.TokenStats
) : JDialog(JOptionPane.getRootFrame(), "Token 用量", ModalityType.MODELESS) {

    private var mode: String = "daily"
    private val content = JPanel(BorderLayout(10, 10)).apply { border = JBUI.Borders.empty(16) }

    companion object {
        @Volatile private var current: TokenDashboard? = null
        fun show(stats: AgentContext.TokenStats) {
            current?.dispose()
            val d = TokenDashboard(stats)
            current = d
            d.isVisible = true
        }
    }

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        size = Dimension(500, 420)
        setLocationRelativeTo(null)
        refreshContent(); add(content)
    }

    private fun refreshContent() {
        content.removeAll()
        val totalLabel = JLabel("<html>${TokenTracker.getTotalStats(stats).replace("\n", "<br>")}</html>").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.textSecondary
        }
        content.add(totalLabel, BorderLayout.NORTH)

        val togglePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val dailyBtn = JButton("按天").apply { addActionListener { mode = "daily"; refreshContent() } }
        val weeklyBtn = JButton("按周").apply { addActionListener { mode = "weekly"; refreshContent() } }
        togglePanel.add(dailyBtn); togglePanel.add(weeklyBtn)
        content.add(togglePanel, BorderLayout.CENTER)

        val summaries = if (mode == "weekly") TokenTracker.getWeeklyStats(stats) else TokenTracker.getDailyStats(stats)
        val columns = arrayOf("日期", "输入", "输出", "轮次")
        val data = summaries.map { arrayOf(it.date, fmt(it.inputTokens), fmt(it.outputTokens), it.rounds.toString()) }.toTypedArray()
        val table = JTable(data, columns).apply {
            setFillsViewportHeight(true)
            getColumnModel().getColumn(0).setPreferredWidth(100)
            getColumnModel().getColumn(1).setPreferredWidth(80)
            getColumnModel().getColumn(2).setPreferredWidth(80)
            getColumnModel().getColumn(3).setPreferredWidth(60)
        }
        val scroll = JScrollPane(table).apply { preferredSize = Dimension(460, 220) }
        content.add(scroll, BorderLayout.SOUTH)
        content.revalidate(); content.repaint()
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
        n >= 1000 -> "${n / 1000}k" else -> "$n"
    }
}
