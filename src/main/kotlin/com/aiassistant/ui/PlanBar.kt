package com.aiassistant.ui

import com.aiassistant.agent.AgentContext
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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import com.intellij.ui.components.JBScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * 置顶可折叠执行计划条。增量更新，不重建。
 */
class PlanBar : JPanel(BorderLayout()) {

    private var expanded = false
    private var currentPlan: AgentContext.Plan? = null
    private var prevStepStatuses = mutableMapOf<Int, AgentContext.StepStatus>()

    // 缓存的 UI 组件引用（增量更新用）
    private var summaryProgressLabel: JLabel? = null
    private var summaryStepDescLabel: JLabel? = null
    private var summaryMiniBar: MiniProgressBar? = null
    private var stepMarkerLabels = mutableMapOf<Int, JLabel>()
    private var stepNameLabels = mutableMapOf<Int, JLabel>()

    init {
        isOpaque = false
        isVisible = false
    }

    /** 更新计划：同引用走增量，异引用或 null 走重建 */
    fun updatePlan(plan: AgentContext.Plan?) {
        if (plan == null || plan.stepsSnapshot().isEmpty() || plan.isComplete()) {
            isVisible = false
            removeOverlay()
            parent?.revalidate(); revalidate(); repaint()
            currentPlan = null
            clearComponentRefs()
            prevStepStatuses.clear()
            return
        }
        val samePlan = plan === currentPlan
        currentPlan = plan
        if (!samePlan) {
            // 新计划 → 全量重建
            clearComponentRefs()
            prevStepStatuses.clear()
            isVisible = true
            rebuild()
            parent?.revalidate()
        } else {
            // 同引用 → 增量更新
            isVisible = true
            incrementalUpdate(plan)
        }
    }

    // ---- 增量更新 ----

    private fun incrementalUpdate(plan: AgentContext.Plan) {
        val steps = plan.stepsSnapshot()
        val progress = plan.progress()

        // 更新摘要行进度
        summaryProgressLabel?.text = "  $progress"
        summaryMiniBar?.let { bar ->
            val done = steps.count { it.status == AgentContext.StepStatus.DONE }
            bar.update(done, steps.size)
            bar.repaint()
        }

        // 当前步骤描述
        val currentStep = steps.firstOrNull { it.status == AgentContext.StepStatus.IN_PROGRESS }
            ?: steps.firstOrNull { it.status == AgentContext.StepStatus.PENDING }
        summaryStepDescLabel?.text = if (!expanded && currentStep != null) " · ${currentStep.subject}" else ""

        // 增量更新每行（只改变化的状态）
        for (step in steps) {
            val prev = prevStepStatuses[step.index]
            if (prev == step.status) continue  // 未变，跳过

            // 更新状态记录
            prevStepStatuses[step.index] = step.status

            // 更新标记符号
            val (marker, markerColor) = when (step.status) {
                AgentContext.StepStatus.DONE -> "☑" to ChatTheme.doneCheck
                AgentContext.StepStatus.IN_PROGRESS -> "◉" to ChatTheme.toolBar
                AgentContext.StepStatus.PENDING -> "☐" to ChatTheme.textSecondary
                AgentContext.StepStatus.FAILED -> "✕" to ChatTheme.error
            }
            stepMarkerLabels[step.index]?.let {
                it.text = "$marker  "
                it.foreground = markerColor
            }

            // 更新名称样式
            val (nameColor, nameStyle) = when (step.status) {
                AgentContext.StepStatus.DONE -> ChatTheme.textMuted to Font.PLAIN
                AgentContext.StepStatus.IN_PROGRESS -> ChatTheme.textPrimary to Font.BOLD
                AgentContext.StepStatus.PENDING -> ChatTheme.textSecondary to Font.PLAIN
                AgentContext.StepStatus.FAILED -> ChatTheme.error to Font.PLAIN
            }
            stepNameLabels[step.index]?.let {
                it.font = ChatTheme.metaFont.deriveFont(nameStyle.toFloat())
                it.foreground = nameColor
            }
        }

        // 如果新步骤被追加（appendSteps），需要重建
        if (stepMarkerLabels.size != steps.size) {
            rebuild()
        }
    }

    private fun clearComponentRefs() {
        summaryProgressLabel = null
        summaryStepDescLabel = null
        summaryMiniBar = null
        stepMarkerLabels.clear()
        stepNameLabels.clear()
    }

    // ---- 全量构建 ----

    private fun rebuild() {
        removeAll()
        clearComponentRefs()
        val plan = currentPlan ?: return

        // 摘要行
        add(buildSummaryRow(plan), BorderLayout.NORTH)

        // 分隔线
        add(object : JPanel() {
            override fun getPreferredSize() = Dimension(Int.MAX_VALUE, 1)
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 1)
            override fun paintComponent(g: Graphics) { g.create().also { g2 -> g2.color = ChatTheme.divider; g2.fillRect(0, 0, width, height); (g2 as Graphics2D).dispose() } }
        }.apply { isOpaque = false }, BorderLayout.SOUTH)

        // 展开
        removeOverlay()
        if (expanded) {
            val stepScroll = buildStepList(plan)
            stepScroll.border = BorderFactory.createLineBorder(ChatTheme.divider, 1)
            stepScroll.background = ChatTheme.winBg
            stepScroll.isOpaque = true
            overlay = stepScroll
            val rootPane = SwingUtilities.getRootPane(this)
            if (rootPane != null) {
                val pt = SwingUtilities.convertPoint(this, 0, height, rootPane.layeredPane)
                stepScroll.setBounds(pt.x, pt.y, rootPane.layeredPane.width, minOf(stepScroll.preferredSize.height, ChatTheme.PLAN_STEP_MAX_H))
                rootPane.layeredPane.add(stepScroll, JLayeredPane.POPUP_LAYER)
                rootPane.layeredPane.revalidate(); rootPane.layeredPane.repaint()
                overlayClickListener?.let { rootPane.layeredPane.removeMouseListener(it) }
                overlayClickListener = object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val comp = rootPane.layeredPane.getComponentAt(e.point)
                        if (comp != stepScroll && !SwingUtilities.isDescendingFrom(comp, stepScroll)) {
                            expanded = false; rebuild()
                        }
                    }
                }
                rootPane.layeredPane.addMouseListener(overlayClickListener!!)
            }
        }

        // 记录初始状态用于增量比较
        prevStepStatuses.clear()
        plan.stepsSnapshot().forEach { prevStepStatuses[it.index] = it.status }

        revalidate(); repaint()
    }

    private var overlay: JComponent? = null
    private var overlayClickListener: MouseAdapter? = null

    private fun removeOverlay() {
        overlay?.let {
            val rootPane = SwingUtilities.getRootPane(this)
            overlayClickListener?.let { rootPane?.layeredPane?.removeMouseListener(it) }
            overlayClickListener = null
            rootPane?.layeredPane?.remove(it); rootPane?.layeredPane?.repaint()
        }
        overlay = null
    }

    // ---- 摘要行 ----

    private fun buildSummaryRow(plan: AgentContext.Plan): JPanel {
        val chevron = if (expanded) "▾" else "▸"
        val progress = plan.progress()
        val steps = plan.stepsSnapshot()
        val currentStep = steps.firstOrNull { it.status == AgentContext.StepStatus.IN_PROGRESS }
            ?: steps.firstOrNull { it.status == AgentContext.StepStatus.PENDING }
        val stepDesc = if (!expanded && currentStep != null) " · ${currentStep.subject}" else ""

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 10, 5, 10)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        val leftPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }

        leftPanel.add(JLabel("$chevron ").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.textSecondary
        })
        leftPanel.add(JLabel(plan.title).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.textSecondary
        })
        val progLabel = JLabel("  $progress").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.toolBar
        }
        leftPanel.add(progLabel)
        summaryProgressLabel = progLabel

        if (stepDesc.isNotEmpty()) {
            val descLabel = JLabel(stepDesc).apply { font = ChatTheme.metaFont; foreground = ChatTheme.textMuted }
            leftPanel.add(descLabel)
            summaryStepDescLabel = descLabel
        }
        leftPanel.add(Box.createHorizontalGlue())
        row.add(leftPanel, BorderLayout.CENTER)

        if (!expanded) {
            val done = steps.count { it.status == AgentContext.StepStatus.DONE }
            val miniBar = MiniProgressBar(done, steps.size)
            row.add(miniBar, BorderLayout.EAST)
            summaryMiniBar = miniBar
        }

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { expanded = !expanded; rebuild() }
        })
        return row
    }

    // ---- 步骤列表 ----

    private fun buildStepList(plan: AgentContext.Plan): JBScrollPane {
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            border = JBUI.Borders.empty(2, 10, 6, 10)
        }
        for (step in plan.stepsSnapshot()) {
            listPanel.add(buildStepRow(step))
        }
        return JBScrollPane(listPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty(); isOpaque = false; viewport.isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, ChatTheme.PLAN_STEP_MAX_H)
            preferredSize = Dimension(preferredSize.width, minOf(listPanel.preferredSize.height + 4, ChatTheme.PLAN_STEP_MAX_H))
        }
    }

    private fun buildStepRow(step: AgentContext.Step): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, ChatTheme.PLAN_STEP_ROW_H)
        }
        val (marker, markerColor) = when (step.status) {
            AgentContext.StepStatus.DONE -> "☑" to ChatTheme.doneCheck
            AgentContext.StepStatus.IN_PROGRESS -> "◉" to ChatTheme.toolBar
            AgentContext.StepStatus.PENDING -> "☐" to ChatTheme.textSecondary
            AgentContext.StepStatus.FAILED -> "✕" to ChatTheme.error
        }
        val markerLabel = JLabel("$marker  ").apply { font = ChatTheme.metaFont; foreground = markerColor }
        row.add(markerLabel)
        stepMarkerLabels[step.index] = markerLabel

        val (nameColor, nameStyle) = when (step.status) {
            AgentContext.StepStatus.DONE -> ChatTheme.textMuted to Font.PLAIN
            AgentContext.StepStatus.IN_PROGRESS -> ChatTheme.textPrimary to Font.BOLD
            AgentContext.StepStatus.PENDING -> ChatTheme.textSecondary to Font.PLAIN
            AgentContext.StepStatus.FAILED -> ChatTheme.error to Font.PLAIN
        }
        val nameLabel = JLabel(step.subject).apply {
            font = ChatTheme.metaFont.deriveFont(nameStyle.toFloat()); foreground = nameColor
            horizontalAlignment = SwingConstants.LEFT
        }
        row.add(nameLabel)
        stepNameLabels[step.index] = nameLabel

        if (step.description.isNotBlank()) {
            row.add(JLabel("  ${step.description}").apply {
                font = ChatTheme.metaFont.deriveFont(Font.PLAIN, ChatTheme.metaFont.size2D - ChatTheme.META_FONT_OFFSET)
                foreground = ChatTheme.textMuted; horizontalAlignment = SwingConstants.LEFT
            })
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    // ---- 迷你进度条 ----

    private inner class MiniProgressBar(private var done: Int, private var total: Int) : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(ChatTheme.PLAN_PROGRESS_W, ChatTheme.PLAN_PROGRESS_H)
            minimumSize = Dimension(ChatTheme.PLAN_PROGRESS_W, ChatTheme.PLAN_PROGRESS_H)
            maximumSize = Dimension(ChatTheme.PLAN_PROGRESS_W, ChatTheme.PLAN_PROGRESS_H)
            border = JBUI.Borders.empty(0, 8, 0, 0)
        }

        fun update(d: Int, t: Int) { done = d; total = t }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barX = JBUI.scale(8); val barY = (height - 4) / 2; val barW = width - JBUI.scale(8)
            g2.color = ChatTheme.divider
            g2.fillRoundRect(barX, barY, barW, 4, ChatTheme.RADIUS_PROGRESS, ChatTheme.RADIUS_PROGRESS)
            if (total > 0) {
                val filledW = (barW * done.toFloat() / total).toInt()
                if (filledW > 0) {
                    g2.color = ChatTheme.toolBar
                    g2.fillRoundRect(barX, barY, filledW, 4, ChatTheme.RADIUS_PROGRESS, ChatTheme.RADIUS_PROGRESS)
                }
            }
            g2.dispose()
        }
    }
}
