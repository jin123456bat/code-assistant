package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

// 计划卡片 — 步骤进度 + 控制按钮，支持亮/暗主题，支持折叠/展开（用户可随时折叠，不受执行状态限制）
// Plan 状态样式规范见 docs/ui/chat.md §五

class PlanCard(
    private val onDeleteStep: (stepId: String) -> Unit = {}
) : JPanel(BorderLayout()) {

    private val header = JPanel(BorderLayout())
    private val arrowLabel = JLabel("▶")
    private val summaryLabel = JLabel()
    private val progressLabel = JLabel()
    private val stepsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private val steps = mutableListOf<StepRow>()
    private var currentStepIndex = 0

    /** 当前折叠状态 */
    private var isExpanded = false

    /** 当前 PlanCard 的整体状态 */
    private var planState: PlanState = PlanState.PAUSED

    enum class PlanState {
        PAUSED, EXECUTING, COMPLETED, CANCELLED
    }

    data class StepRow(
        val id: String,
        val description: String,
        val files: String
    ) {
        val label = JLabel()
    }

    init {
        // 对齐 ui-prototype.html .plan-card: border-bottom=1px solid #E5E7EB, padding=10px 16px
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        )
        isOpaque = true
        background = AppColors.cardBg

        // 头部区域：箭头 + 标题 + 进度，可点击折叠/展开
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggleExpanded()
            }
        })
        arrowLabel.font = arrowLabel.font.deriveFont(10f)
        summaryLabel.font = summaryLabel.font.deriveFont(12f).deriveFont(Font.BOLD)
        progressLabel.font = progressLabel.font.deriveFont(10f)

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        headerLeft.add(arrowLabel)
        headerLeft.add(summaryLabel)
        header.add(headerLeft, BorderLayout.WEST)
        header.add(progressLabel, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)

        add(stepsPanel, BorderLayout.CENTER)

        // 默认折叠状态
        applyExpandedState()
    }

    /** 切换折叠/展开状态 */
    fun toggleExpanded() {
        isExpanded = !isExpanded
        applyExpandedState()
    }

    /** 设置折叠状态 */
    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        applyExpandedState()
    }

    /**
     * 设置计划是否处于 EXECUTING 状态。
     * EXECUTING 状态下自动展开，但用户仍可手动折叠。
     */
    fun setExecutingState(executing: Boolean) {
        if (executing) {
            isExpanded = true
            planState = PlanState.EXECUTING
        }
        applyExpandedState()
    }

    fun setPlan(summary: String, planSteps: List<StepRow>) {
        summaryLabel.text = "📋 $summary"
        steps.clear()
        stepsPanel.removeAll()
        planSteps.forEach { addStep(it) }
        currentStepIndex = 0
        if (planState != PlanState.EXECUTING) planState = PlanState.PAUSED
        updateProgressLabel()
        applyExpandedState()
    }

    fun addStep(step: StepRow) {
        steps.add(step)
        step.label.apply {
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (planState == PlanState.PAUSED) {
                        val stepIndex = steps.indexOf(step)
                        // 只有待执行步骤（PAUSED 状态且未完成）才能删除
                        if (stepIndex >= currentStepIndex) {
                            onDeleteStep(step.id)
                        }
                    }
                }
            })
        }
        stepsPanel.add(step.label)
        refreshStepLabels()
    }

    /** 标记步骤完成（COMPLETED 样式：fg=#22C55E, bg=transparent） */
    fun setStepDone(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val green = AppColors.success.toHtmlColor()
            step.label.text =
                "<html><span style='color:$green'>✅ ${step.description}</span></html>"
        }
        currentStepIndex = index + 1
        updateProgressLabel()
        refreshStepLabels()
        checkAutoDisappear()
    }

    /** 标记步骤执行中（EXECUTING 样式：fg=#3B82F6, bg=#EFF6FF, 左侧蓝色竖线 2px） */
    fun setStepExecuting(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val blue = "#3B82F6"
            val blueBg = AppColors.primaryLight.toHtmlColor()
            step.label.text =
                "<html><div style='color:$blue;background:$blueBg;padding:2px 4px;border-left:2px solid $blue;'>🔄 ${step.description}</div></html>"
        }
        currentStepIndex = index
        planState = PlanState.EXECUTING
        updateProgressLabel()
    }

    /** 标记步骤错误（对齐 ui-prototype .step-error: bg=#FEE2E2, border-radius=4px） */
    fun setStepError(index: Int, msg: String) {
        steps.getOrNull(index)?.let { step ->
            val err = AppColors.error.toHtmlColor()
            val errBg = AppColors.errorBg.toHtmlColor()
            step.label.text =
                "<html><div style='color:$err;background:$errBg;padding:2px 4px;'>❌ ${step.description}: $msg</div></html>"
        }
    }

    /** 标记步骤跳过 */
    fun setStepSkipped(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val dim = AppColors.textSecondary.toHtmlColor()
            step.label.text =
                "<html><span style='color:$dim;text-decoration:line-through'>⏭ ${step.description}</span></html>"
        }
    }

    /** 标记整个计划已被取消（CANCELLED 样式：fg=#9CA3AF, 删除线） */
    fun setPlanCancelled() {
        planState = PlanState.CANCELLED
        steps.forEachIndexed { i, step ->
            if (i >= currentStepIndex) {
                val gray = "#9CA3AF"
                step.label.text =
                    "<html><span style='color:$gray;text-decoration:line-through'>🗑 ${step.description}</span></html>"
            }
        }
        checkAutoDisappear()
    }

    private fun updateProgressLabel() {
        val doneCount = steps.count { it.label.text?.contains("✅") == true }
        val total = steps.size
        progressLabel.text = "$doneCount/$total 已完成"
    }

    /**
     * 应用当前的折叠/展开状态。
     * 折叠状态下仅显示头部（标题 + 摘要 + 进度）和当前执行项；展开状态下显示全部内容。
     */
    private fun applyExpandedState() {
        if (isExpanded) {
            // 展开：显示所有步骤项
            arrowLabel.text = "▾"
            steps.forEach { it.label.isVisible = true }
            stepsPanel.isVisible = true
        } else {
            // 折叠：仅显示头部 + 当前执行中的步骤
            arrowLabel.text = "▶"
            if (steps.isNotEmpty()) {
                val executableIndex = currentStepIndex.coerceIn(0, steps.size - 1)
                steps.forEachIndexed { i, step ->
                    step.label.isVisible = i == executableIndex
                }
            }
            stepsPanel.isVisible = steps.isNotEmpty()
        }
        stepsPanel.revalidate()
        stepsPanel.repaint()
        revalidate()
        repaint()
    }

    private fun refreshStepLabels() {
        val secondary = AppColors.textSecondary.toHtmlColor()
        val green = AppColors.success.toHtmlColor()
        val gray = "#6B7280"
        val deleteColor = AppColors.error.toHtmlColor()

        steps.forEachIndexed { i, step ->
            val currentText = step.label.text ?: ""
            when {
                // 已完成步骤（COMPLETED: fg=#22C55E, bg=transparent）
                i < currentStepIndex && !currentText.contains("❌") && !currentText.contains("🗑") -> {
                    step.label.text =
                        "<html><span style='color:$green'>✅ ${step.description}</span></html>"
                }
                // 当前执行中步骤已由 setStepExecuting 设置，跳过覆盖
                i == currentStepIndex && planState == PlanState.EXECUTING -> {
                    // 保持 setStepExecuting 设置的样式不变
                }
                // 待执行步骤（PAUSED: fg=#6B7280, bg=transparent, 行末 [✕] 可见）
                i > currentStepIndex && !currentText.contains("⬜") -> {
                    step.label.text =
                        "<html><span style='color:$gray'>⬜ ${step.description}</span> <span style='color:$secondary;font-size:11px'>${step.files}</span>" +
                                " <span style='color:$deleteColor;cursor:pointer'>[✕]</span></html>"
                }
                // 当前步骤在 PAUSED 状态下显示 PAUSED 样式（行末 [✕] 可见）
                i == currentStepIndex && planState == PlanState.PAUSED && !currentText.contains("⬜") -> {
                    step.label.text =
                        "<html><span style='color:$gray'>⬜ ${step.description}</span> <span style='color:$secondary;font-size:11px'>${step.files}</span>" +
                                " <span style='color:$deleteColor;cursor:pointer'>[✕]</span></html>"
                }
            }
        }
        updateProgressLabel()
    }

    /** 全部步骤 COMPLETED 或 CANCELLED 后 PlanCard 消失 */
    private fun checkAutoDisappear() {
        val allDone = steps.all {
            it.label.text?.contains("✅") == true
        }
        val allCancelled = planState == PlanState.CANCELLED && steps.all {
            it.label.text?.contains("🗑") == true || it.label.text?.contains("✅") == true
        }
        if (allDone || allCancelled) {
            isVisible = false
            revalidate()
            repaint()
        }
    }
}
