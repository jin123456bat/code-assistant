package com.aiassistant.ui

import com.aiassistant.agent.Task
import com.aiassistant.agent.TaskStatus
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
 * 置顶可折叠计划/任务条。对齐 Claude Code PlanBar + Task 系统。
 * 三种状态：
 *   规划模式中 → "◉ 规划中…" spinner
 *   计划已批准，无任务 → 标题 +"已批准"+ 点击展开看全文
 *   有任务 → 任务列表 + 进度条 + 点击展开
 */
class PlanBar : JPanel(BorderLayout()) {

    private var expanded = false

    // 外部注入的状态（由 ChatToolWindow 在回调中更新）
    private var planMode: Boolean = false
    private var planTitle: String? = null
    private var planText: String? = null
    private var tasks: List<Task> = emptyList()

    // 缓存的 UI 组件引用（增量更新用）
    private var summaryProgressLabel: JLabel? = null
    private var summaryDescLabel: JLabel? = null
    private var summaryMiniBar: MiniProgressBar? = null
    private var taskMarkerLabels = mutableMapOf<Int, JLabel>()
    private var taskNameLabels = mutableMapOf<Int, JLabel>()
    private var prevTaskVersions = mutableMapOf<Int, TaskSnapshot>()
    private data class TaskSnapshot(val status: TaskStatus, val result: String?)

    init {
        isOpaque = false
        isVisible = false
    }

    /** 全量更新状态（由 ChatToolWindow 调用） */
    fun updateState(
        planMode: Boolean,
        planTitle: String?,
        planText: String?,
        tasks: List<Task>
    ) {
        val oldVisible = isVisible
        val newVisible = planMode || planTitle != null || tasks.isNotEmpty()
        // 检测是否需要重建（引用变化或任务数变化）
        val tasksChanged = this.tasks.size != tasks.size ||
            tasks.zip(this.tasks).any { (a, b) -> a.id != b.id || a.status != b.status }

        this.planMode = planMode
        this.planTitle = planTitle
        this.planText = planText
        this.tasks = tasks

        if (!newVisible) {
            isVisible = false
            removeOverlay()
            clearComponentRefs()
            prevTaskVersions.clear()
            parent?.revalidate(); revalidate(); repaint()
            return
        }

        if (newVisible && !oldVisible || !tasksChanged && tasks.isEmpty()) {
            // 首次可见 或 仅 plan 状态变化 → 全量重建
            clearComponentRefs()
            prevTaskVersions.clear()
            isVisible = true
            rebuild()
        } else if (tasksChanged) {
            // 任务变化 → 增量更新
            isVisible = true
            incrementalUpdate()
        }
        parent?.revalidate()
    }

    private fun clearComponentRefs() {
        summaryProgressLabel = null
        summaryDescLabel = null
        summaryMiniBar = null
        taskMarkerLabels.clear()
        taskNameLabels.clear()
    }

    // ---- 构建 ----

    private fun rebuild() {
        removeAll()
        clearComponentRefs()

        when {
            planMode -> add(buildPlanModeRow(), BorderLayout.NORTH)
            planTitle != null -> add(buildSummaryRow(), BorderLayout.NORTH)
            tasks.isNotEmpty() -> add(buildSummaryRow(), BorderLayout.NORTH)
        }

        add(object : JPanel() {
            override fun getPreferredSize() = Dimension(Int.MAX_VALUE, 1)
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, 1)
            override fun paintComponent(g: Graphics) { g.create().also { g2 -> g2.color = ChatTheme.divider; g2.fillRect(0, 0, width, height); (g2 as Graphics2D).dispose() } }
        }.apply { isOpaque = false }, BorderLayout.SOUTH)

        removeOverlay()
        if (expanded) {
            val content = when {
                planText != null && tasks.isEmpty() -> buildPlanContent()
                tasks.isNotEmpty() -> buildTaskList()
                else -> null
            }
            if (content != null) {
                content.border = javax.swing.BorderFactory.createLineBorder(ChatTheme.divider, 1)
                content.background = ChatTheme.winBg
                content.isOpaque = true
                overlay = content
                val rootPane = SwingUtilities.getRootPane(this)
                if (rootPane != null) {
                    val pt = SwingUtilities.convertPoint(this, 0, height, rootPane.layeredPane)
                    content.setBounds(pt.x, pt.y, rootPane.layeredPane.width, minOf(content.preferredSize.height, ChatTheme.PLAN_STEP_MAX_H))
                    rootPane.layeredPane.add(content, JLayeredPane.POPUP_LAYER)
                    rootPane.layeredPane.revalidate(); rootPane.layeredPane.repaint()
                    overlayClickListener?.let { rootPane.layeredPane.removeMouseListener(it) }
                    overlayClickListener = object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            val comp = rootPane.layeredPane.getComponentAt(e.point)
                            if (comp != content && !SwingUtilities.isDescendingFrom(comp, content)) {
                                expanded = false; rebuild()
                            }
                        }
                    }
                    rootPane.layeredPane.addMouseListener(overlayClickListener!!)
                }
            }
        }

        // 记录初始状态用于增量比较
        prevTaskVersions.clear()
        tasks.forEach { prevTaskVersions[it.id] = TaskSnapshot(it.status, it.result) }

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

    // ---- 规划模式行 ----

    private fun buildPlanModeRow(): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 10, 5, 10)
        }
        row.add(JLabel("◉ 规划中…").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.toolBar
        }, BorderLayout.WEST)
        return row
    }

    // ---- 摘要行 ----

    private fun buildSummaryRow(): JPanel {
        val chevron = if (expanded) "▾" else "▸"
        val title = planTitle ?: "任务"
        val done = tasks.count { it.status == TaskStatus.COMPLETED }
        val total = tasks.size
        val progress = if (total > 0) "$done/$total" else null
        val currentTask = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            ?: tasks.firstOrNull { it.status == TaskStatus.PENDING }
        val desc = if (!expanded && currentTask != null) " · ${currentTask.subject}" else ""

        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 10, 5, 10)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        val leftPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }

        leftPanel.add(JLabel("$chevron ").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.textSecondary
        })
        leftPanel.add(JLabel(title).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.textSecondary
        })
        if (progress != null) {
            val progLabel = JLabel("  $progress").apply {
                font = ChatTheme.metaFont.deriveFont(Font.BOLD); foreground = ChatTheme.toolBar
            }
            leftPanel.add(progLabel)
            summaryProgressLabel = progLabel
        }
        if (desc.isNotEmpty()) {
            val descLabel = JLabel(desc).apply { font = ChatTheme.metaFont; foreground = ChatTheme.textMuted }
            leftPanel.add(descLabel)
            summaryDescLabel = descLabel
        }
        if (planText != null && tasks.isEmpty()) {
            leftPanel.add(JLabel("  已批准").apply {
                font = ChatTheme.metaFont.deriveFont(Font.PLAIN, ChatTheme.metaFont.size2D - ChatTheme.META_FONT_OFFSET)
                foreground = ChatTheme.doneCheck
            })
        }
        leftPanel.add(Box.createHorizontalGlue())
        row.add(leftPanel, BorderLayout.CENTER)

        if (!expanded && total > 0) {
            val miniBar = MiniProgressBar(done, total)
            row.add(miniBar, BorderLayout.EAST)
            summaryMiniBar = miniBar
        }

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { expanded = !expanded; rebuild() }
        })
        return row
    }

    // ---- 计划内容（markdown 文本） ----

    private fun buildPlanContent(): JBScrollPane {
        val textPane = javax.swing.JTextPane().apply {
            contentType = "text/html"
            isEditable = false
            isOpaque = false
            // 简单 markdown → html 转换
            text = "<html><body style='font-family:sans-serif;padding:8px;color:#${Integer.toHexString(ChatTheme.textPrimary.rgb and 0xFFFFFF)}'>" +
                planText?.replace("\n", "<br>")?.replace("`", "<code>")?.replace("`", "</code>") ?: "" +
                "</body></html>"
        }
        return JBScrollPane(textPane, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty(); isOpaque = false; viewport.isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, ChatTheme.PLAN_STEP_MAX_H)
            preferredSize = Dimension(500, minOf(textPane.preferredSize.height + 8, ChatTheme.PLAN_STEP_MAX_H))
        }
    }

    // ---- 任务列表 ----

    private fun buildTaskList(): JBScrollPane {
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            border = JBUI.Borders.empty(2, 10, 6, 10)
        }
        for (task in tasks) {
            listPanel.add(buildTaskRow(task))
        }
        return JBScrollPane(listPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty(); isOpaque = false; viewport.isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, ChatTheme.PLAN_STEP_MAX_H)
            preferredSize = Dimension(preferredSize.width, minOf(listPanel.preferredSize.height + 4, ChatTheme.PLAN_STEP_MAX_H))
        }
    }

    private fun buildTaskRow(task: Task): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, ChatTheme.PLAN_STEP_ROW_H)
        }
        val (marker, markerColor) = when (task.status) {
            TaskStatus.COMPLETED -> "☑" to ChatTheme.doneCheck
            TaskStatus.IN_PROGRESS -> "◉" to ChatTheme.toolBar
            TaskStatus.PENDING -> "☐" to ChatTheme.textSecondary
        }
        val markerLabel = JLabel("$marker  ").apply { font = ChatTheme.metaFont; foreground = markerColor }
        row.add(markerLabel)
        taskMarkerLabels[task.id] = markerLabel

        val (nameColor, nameStyle) = when (task.status) {
            TaskStatus.COMPLETED -> ChatTheme.textMuted to Font.PLAIN
            TaskStatus.IN_PROGRESS -> ChatTheme.textPrimary to Font.BOLD
            TaskStatus.PENDING -> ChatTheme.textSecondary to Font.PLAIN
        }
        val nameLabel = JLabel("#${task.id} ${task.subject}").apply {
            font = ChatTheme.metaFont.deriveFont(nameStyle.toFloat()); foreground = nameColor
        }
        row.add(nameLabel)
        taskNameLabels[task.id] = nameLabel

        if (task.description.isNotBlank()) {
            row.add(JLabel("  ${task.description}").apply {
                font = ChatTheme.metaFont.deriveFont(Font.PLAIN, ChatTheme.metaFont.size2D - ChatTheme.META_FONT_OFFSET)
                foreground = ChatTheme.textMuted
            })
        }
        if (task.result != null) {
            row.add(JLabel("  → ${task.result}").apply {
                font = ChatTheme.metaFont.deriveFont(Font.ITALIC, ChatTheme.metaFont.size2D - ChatTheme.META_FONT_OFFSET)
                foreground = ChatTheme.textMuted
            })
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    // ---- 增量更新 ----

    private fun incrementalUpdate() {
        // 更新进度标签
        val done = tasks.count { it.status == TaskStatus.COMPLETED }
        summaryProgressLabel?.text = "  $done/${tasks.size}"

        summaryMiniBar?.let { bar ->
            bar.update(done, tasks.size)
            bar.repaint()
        }

        val currentTask = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS }
            ?: tasks.firstOrNull { it.status == TaskStatus.PENDING }
        summaryDescLabel?.text = if (!expanded && currentTask != null) " · #${currentTask.id} ${currentTask.subject}" else ""

        for (task in tasks) {
            val prev = prevTaskVersions[task.id]
            if (prev != null && prev.status == task.status && prev.result == task.result) continue

            prevTaskVersions[task.id] = TaskSnapshot(task.status, task.result)

            val (marker, markerColor) = when (task.status) {
                TaskStatus.COMPLETED -> "☑" to ChatTheme.doneCheck
                TaskStatus.IN_PROGRESS -> "◉" to ChatTheme.toolBar
                TaskStatus.PENDING -> "☐" to ChatTheme.textSecondary
            }
            taskMarkerLabels[task.id]?.let { it.text = "$marker  "; it.foreground = markerColor }

            val (nameColor, nameStyle) = when (task.status) {
                TaskStatus.COMPLETED -> ChatTheme.textMuted to Font.PLAIN
                TaskStatus.IN_PROGRESS -> ChatTheme.textPrimary to Font.BOLD
                TaskStatus.PENDING -> ChatTheme.textSecondary to Font.PLAIN
            }
            taskNameLabels[task.id]?.let {
                it.font = ChatTheme.metaFont.deriveFont(nameStyle.toFloat())
                it.foreground = nameColor
            }
        }

        // 任务 ID 集合变化（新增/删除/替换）→ 全量重建
        if (taskMarkerLabels.keys != tasks.map { it.id }.toSet()) {
            rebuild()
        }
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
