package com.aiassistant.ui.page

import com.aiassistant.session.SessionIndex
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.DefaultTableModel

class TokenUsagePage(project: Project) : JPanel(BorderLayout()) {

    private val store = SessionStore(project)
    private var includeChildSessions = false
    private var currentPage = 0
    private val pageSize = 20
    private val body = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); border =
        BorderFactory.createEmptyBorder(8, 0, 8, 0)
    }
    private val totalConsumptionLabel = JLabel()

    init {
        val rangeBox = JComboBox(arrayOf("本月", "今日", "总计")).apply {
            addActionListener { refreshView(store.listAll(), (selectedItem as String)) }
        }
        val includeChildCheckbox = JCheckBox("含子任务").apply {
            toolTipText = "勾选后，父 session 的 token 统计递归聚合所有子 session"
            addActionListener {
                includeChildSessions = isSelected
                refreshView(store.listAll(), rangeBox.selectedItem as String)
            }
        }
        val headerPanel = JPanel(BorderLayout())
        val leftPanel = JPanel().apply {
            add(JLabel("时间范围: "))
            add(rangeBox)
            add(includeChildCheckbox)
        }
        headerPanel.add(leftPanel, BorderLayout.WEST)
        headerPanel.add(totalConsumptionLabel, BorderLayout.EAST)
        add(headerPanel, BorderLayout.NORTH)
        add(JScrollPane(body).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }, BorderLayout.CENTER)
        refreshView(store.listAll(), "本月")
    }

    /**
     * 递归聚合子 session token。对齐 docs/ui/pages.md §五：
     * 父 session 的 token 统计 = 自身 totalTokens + 所有子孙 session 的 totalTokens 之和。
     */
    private fun computeRecursiveTokens(
        sessionId: String,
        allSessions: List<SessionIndex>
    ): Long {
        val self = allSessions.find { it.id == sessionId } ?: return 0
        val childrenTokens = allSessions
            .filter { it.parentId == sessionId }
            .sumOf { computeRecursiveTokens(it.id, allSessions) }
        return self.totalTokens + childrenTokens
    }

    private fun refreshView(sessions: List<SessionIndex>, range: String) {
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

        // 勾选“含子任务”时，只保留顶层 session（parentId 为空），并递归聚合 token。
        // 子 session 不单独显示（其消耗已合并到父 session）。
        val displaySessions = if (includeChildSessions) {
            filtered.filter { it.parentId == null }.map { parent ->
                val aggregatedTokens = computeRecursiveTokens(parent.id, filtered)
                parent.copy(totalTokens = aggregatedTokens)
            }
        } else {
            filtered
        }

        val total = displaySessions.sumOf { it.totalTokens }
        val cost = String.format("%.2f", total * 0.000007)
        totalConsumptionLabel.text = "总消耗: ${total / 1000}K · ¥$cost"

        if (displaySessions.isEmpty()) {
            body.add(renderEmpty())
            body.revalidate(); body.repaint()
            return
        }

        body.add(Box.createVerticalStrut(12))

        // 按日聚合 token 消耗（最近 30 天），对齐 docs/ui/pages.md §五 sparkline 要求
        val now = LocalDate.now(ZoneId.systemDefault())
        val dailyTokensByDate = displaySessions
            .groupBy { LocalDate.ofInstant(it.updatedAt, ZoneId.systemDefault()) }
            .mapValues { entry -> entry.value.sumOf { it.totalTokens } }
        val dailyData = (29 downTo 0).map { daysAgo ->
            val date = now.minusDays(daysAgo.toLong())
            date to (dailyTokensByDate[date] ?: 0L)
        }

        val sparkline = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                g.color = AppColors.primary
                val values = dailyData.map { it.second.toFloat() }
                if (values.isEmpty()) return
                val max = values.max()
                if (max == 0f) return
                val padding = 4
                val chartHeight = height - padding * 2
                val stepX = width.toFloat() / (values.size - 1).coerceAtLeast(1)
                // 计算每个数据点的 (x, y) 坐标
                val points = values.mapIndexed { i, v ->
                    val x = if (values.size == 1) width / 2f else i * stepX
                    val y = padding + chartHeight - (v / max * chartHeight)
                    intArrayOf(x.toInt(), y.toInt())
                }
                // 绘制折线：用 drawPolyline 连接各数据点形成趋势线
                val xPoints = points.map { it[0] }.toIntArray()
                val yPoints = points.map { it[1] }.toIntArray()
                g.drawPolyline(xPoints, yPoints, points.size)
            }
        }
        sparkline.minimumSize = Dimension(200, 60); sparkline.preferredSize = Dimension(200, 80)
        sparkline.border = BorderFactory.createLineBorder(AppColors.border)

        // MouseMotionListener：hover 任意点显示当日日期和具体数值
        val dateFormatter = DateTimeFormatter.ofPattern("MM月dd日")
        sparkline.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val dataSize = dailyData.size
                if (dataSize == 0 || sparkline.width == 0) {
                    sparkline.toolTipText = null
                    return
                }
                val stepX = sparkline.width.toFloat() / (dataSize - 1).coerceAtLeast(1)
                val index = (e.x / stepX).toInt().coerceIn(0, dataSize - 1)
                val (date, tokens) = dailyData[index]
                sparkline.toolTipText = "${date.format(dateFormatter)}: ${tokens / 1000}K tokens"
            }
        })
        sparkline.toolTipText = null
        body.add(sparkline)
        body.add(Box.createVerticalStrut(8))
        val dimHex = AppColors.textSecondary.toHtmlColor()
        body.add(JLabel("<html><span style='color:$dimHex;font-size:10px'>1 日 — 最近 30 天</span></html>"))
        body.add(Box.createVerticalStrut(12))
        body.add(JLabel("<html><b>按会话</b></html>"))
        body.add(Box.createVerticalStrut(6))

        // 分页：每页 pageSize 条，按 updatedAt 降序排列
        val sortedSessions = displaySessions.sortedByDescending { it.updatedAt }
        val totalPages = ((sortedSessions.size - 1) / pageSize).coerceAtLeast(0)
        if (currentPage > totalPages) currentPage = totalPages
        val pageStart = currentPage * pageSize
        val pageSessions = sortedSessions.drop(pageStart).take(pageSize)

        body.add(renderSessionTable(pageSessions))

        // 分页翻页控件：上一页 / 页码 / 下一页
        if (totalPages > 0) {
            body.add(Box.createVerticalStrut(4))
            body.add(renderPaginationControls(currentPage, totalPages, displaySessions, range))
        }

        body.add(Box.createVerticalStrut(8))
        body.add(JLabel("<html><span style='color:$dimHex;font-size:11px'>模型: deepseek-v4-pro（固定）</span></html>"))
        body.revalidate(); body.repaint()
    }

    private fun renderSessionTable(sessions: List<SessionIndex>): JScrollPane {
        val rows = sessions.map { session ->
            arrayOf(
                session.title,
                "${session.totalTokens / 1000}K",
                "¥${String.format("%.2f", session.totalTokens * 0.000007)}"
            )
        }.toTypedArray()
        val model = object : DefaultTableModel(rows, arrayOf("会话", "Tokens", "成本")) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val table = JTable(model).apply {
            rowHeight = 28
            fillsViewportHeight = true
            tableHeader.reorderingAllowed = false
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
        }
        return JScrollPane(table).apply {
            border = BorderFactory.createLineBorder(AppColors.border)
            preferredSize = Dimension(360, 180)
            maximumSize = Dimension(Int.MAX_VALUE, 180)
        }
    }

    /**
     * 渲染分页翻页控件：上一页 / 第 N/共 M 页 / 下一页。
     * 对齐 docs/ui/pages.md §五 会话表格旁标注 "（分页，每页 20 条）"。
     */
    private fun renderPaginationControls(
        current: Int,
        total: Int,
        allSessions: List<SessionIndex>,
        range: String
    ): JPanel {
        val panel = JPanel()
        val prevBtn = JButton("上一页").apply {
            isEnabled = current > 0
            addActionListener {
                currentPage = current - 1
                refreshView(allSessions, range)
            }
        }
        val nextBtn = JButton("下一页").apply {
            isEnabled = current < total
            addActionListener {
                currentPage = current + 1
                refreshView(allSessions, range)
            }
        }
        val label = JLabel("第 ${current + 1}/${total + 1} 页")
        panel.add(prevBtn)
        panel.add(label)
        panel.add(nextBtn)
        return panel
    }

    private fun renderEmpty(): JPanel {
        val p = JPanel(BorderLayout())
        val dimHex = AppColors.textSecondary.toHtmlColor()
        p.add(
            JLabel(
                "<html><div style='text-align:center;padding:40px;color:$dimHex'>📊<br><br>还没有 token 消耗<br><span style='font-size:11px'>使用 Agent 后这里会显示消耗统计</span></div></html>",
                SwingConstants.CENTER
            )
        )
        return p
    }
}
