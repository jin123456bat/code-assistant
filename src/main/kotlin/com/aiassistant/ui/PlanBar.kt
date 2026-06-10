package com.aiassistant.ui

import com.aiassistant.agent_v3.AgentContext
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * 置顶可折叠执行计划条（M2-B）。
 *
 * 放置在 conversationPanel NORTH 区域，位于消息滚动区域之外，不随消息滚动。
 *
 * 折叠（默认）：单行摘要 — "▸ 执行计划  2/4  · 当前步骤描述" + 右侧迷你进度条。
 * 展开：步骤列表（最高 168px 内部滚动）。
 *
 * 当 plan == null 或 steps 为空时自动隐藏。
 */
class PlanBar : JPanel(BorderLayout()) {

    private var expanded = false
    private var currentPlan: AgentContext.Plan? = null

    init {
        isOpaque = false
        isVisible = false
    }

    /**
     * 更新计划数据并重建 UI。
     * plan == null 或 steps 为空 → 隐藏整个 PlanBar。
     */
    fun updatePlan(plan: AgentContext.Plan?) {
        currentPlan = plan
        if (plan == null || plan.steps.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }
        isVisible = true
        rebuild()
    }

    // ---- 私有构建 ----

    private fun rebuild() {
        removeAll()
        val plan = currentPlan ?: return

        // 外层容器：纵向堆叠 [摘要行, (展开内容)]
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // 摘要行（始终可见，点击切换折叠/展开）
        container.add(buildSummaryRow(plan))

        // 展开内容（步骤列表）
        if (expanded) {
            container.add(buildStepList(plan))
        }

        // 底部分隔线
        val dividerLine = object : JPanel() {
            override fun getPreferredSize() = Dimension(Int.MAX_VALUE, 1)
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 1)
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.color = ChatTheme.divider
                g2.fillRect(0, 0, width, height)
                g2.dispose()
            }
        }.apply { isOpaque = false }

        add(container, BorderLayout.CENTER)
        add(dividerLine, BorderLayout.SOUTH)

        revalidate()
        repaint()
    }

    /**
     * 摘要行：
     * - 折叠："▸ 执行计划  2/4  · <当前步骤描述>"  + 右侧迷你进度条
     * - 展开："▾ 执行计划  2/4"
     */
    private fun buildSummaryRow(plan: AgentContext.Plan): JPanel {
        val chevron = if (expanded) "▾" else "▸"
        val progress = plan.progress()
        val currentStep = plan.steps.firstOrNull { it.status == AgentContext.StepStatus.IN_PROGRESS }
            ?: plan.steps.firstOrNull { it.status == AgentContext.StepStatus.PENDING }
        val stepDesc = if (!expanded && currentStep != null) " · ${currentStep.description}" else ""

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 10, 5, 10)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        // 左侧文字区
        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        // 折叠符号
        leftPanel.add(JLabel("$chevron ").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.textSecondary
        })

        // "执行计划"标题
        leftPanel.add(JLabel("执行计划").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.textSecondary
        })

        // 进度（强调色）
        leftPanel.add(JLabel("  $progress").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolBar
        })

        // 当前步骤描述（折叠时显示）
        if (stepDesc.isNotEmpty()) {
            leftPanel.add(JLabel(stepDesc).apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.textMuted
            })
        }

        leftPanel.add(Box.createHorizontalGlue())

        // 右侧迷你进度条（折叠时显示）
        if (!expanded) {
            val total = plan.steps.size
            val done = plan.steps.count { it.status == AgentContext.StepStatus.DONE }
            val miniBar = MiniProgressBar(done, total)
            row.add(miniBar, BorderLayout.EAST)
        }

        row.add(leftPanel, BorderLayout.CENTER)

        // 点击切换展开/折叠
        val toggleListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = !expanded
                rebuild()
            }
        }
        row.addMouseListener(toggleListener)
        leftPanel.addMouseListener(toggleListener)

        return row
    }

    /**
     * 步骤列表（展开时），最高 168px，超出内部滚动。
     */
    private fun buildStepList(plan: AgentContext.Plan): JScrollPane {
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 10, 6, 10)
        }

        for (step in plan.steps) {
            listPanel.add(buildStepRow(step))
        }

        return JScrollPane(
            listPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 168)
            preferredSize = Dimension(preferredSize.width, minOf(listPanel.preferredSize.height + 4, 168))
        }
    }

    /**
     * 单行步骤：
     * - DONE      ☑  描述（删除线效果 — textMuted）
     * - IN_PROGRESS ◉  描述（加粗 textPrimary，强调色点）
     * - PENDING   ☐  描述（textSecondary）
     * - FAILED    ✕  描述（error 色）
     */
    private fun buildStepRow(step: AgentContext.Step): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 24)
        }

        // 标记符号
        val (marker, markerColor) = when (step.status) {
            AgentContext.StepStatus.DONE -> "☑" to ChatTheme.doneCheck
            AgentContext.StepStatus.IN_PROGRESS -> "◉" to ChatTheme.toolBar
            AgentContext.StepStatus.PENDING -> "☐" to ChatTheme.textSecondary
            AgentContext.StepStatus.FAILED -> "✕" to ChatTheme.error
        }

        row.add(JLabel("$marker  ").apply {
            font = ChatTheme.metaFont
            foreground = markerColor
        })

        // 描述文字
        val (descText, descColor, descStyle) = when (step.status) {
            AgentContext.StepStatus.DONE -> Triple(step.description, ChatTheme.textMuted, Font.PLAIN)
            AgentContext.StepStatus.IN_PROGRESS -> Triple(step.description, ChatTheme.textPrimary, Font.BOLD)
            AgentContext.StepStatus.PENDING -> Triple(step.description, ChatTheme.textSecondary, Font.PLAIN)
            AgentContext.StepStatus.FAILED -> Triple(step.description, ChatTheme.error, Font.PLAIN)
        }

        row.add(JLabel(descText).apply {
            font = ChatTheme.metaFont.deriveFont(descStyle.toFloat())
            foreground = descColor
            horizontalAlignment = SwingConstants.LEFT
        })

        row.add(Box.createHorizontalGlue())
        return row
    }

    /**
     * 迷你进度条：宽 60px，高 4px，圆角，使用强调色填充。
     */
    private inner class MiniProgressBar(private val done: Int, private val total: Int) : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(60, 12)
            minimumSize = Dimension(60, 12)
            maximumSize = Dimension(60, 12)
            border = JBUI.Borders.empty(0, 8, 0, 0)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val barY = (height - 4) / 2
            val barW = width - JBUI.scale(8)  // 减去左侧 padding

            // 背景轨道
            g2.color = ChatTheme.divider
            g2.fillRoundRect(0, barY, barW, 4, 4, 4)

            // 填充部分
            if (total > 0) {
                val filledW = (barW * done.toFloat() / total).toInt()
                if (filledW > 0) {
                    g2.color = ChatTheme.toolBar
                    g2.fillRoundRect(0, barY, filledW, 4, 4, 4)
                }
            }

            g2.dispose()
        }
    }
}
