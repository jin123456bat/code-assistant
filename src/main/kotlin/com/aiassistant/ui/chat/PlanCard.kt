package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

// 计划卡片 — 步骤进度 + 控制按钮，支持亮/暗主题

class PlanCard(
    private val onResume: () -> Unit,
    private val onPause: () -> Unit,
    private val onAbort: () -> Unit
) : JPanel(BorderLayout()) {

    private val stepsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val summaryLabel = JLabel()

    private val steps = mutableListOf<StepRow>()
    private var currentStepIndex = 0

    data class StepRow(
        val id: String,
        val description: String,
        val files: String
    ) {
        val label = JLabel()
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AppColors.border),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        )
        isOpaque = true
        background = AppColors.cardBg

        val header = JPanel(BorderLayout())
        summaryLabel.font = summaryLabel.font.deriveFont(13f).deriveFont(Font.BOLD)
        header.add(summaryLabel, BorderLayout.WEST)
        add(header, BorderLayout.NORTH)

        add(stepsPanel, BorderLayout.CENTER)

        val btns = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        btns.add(JButton("▶ 继续").apply { addActionListener { onResume() } })
        btns.add(JButton("⏸ 暂停").apply { addActionListener { onPause() } })
        btns.add(JButton("✕ 终止").apply { addActionListener { onAbort() } })
        add(btns, BorderLayout.SOUTH)
    }

    fun setPlan(summary: String, planSteps: List<StepRow>) {
        summaryLabel.text = "📋 $summary"
        steps.clear()
        stepsPanel.removeAll()
        planSteps.forEach { addStep(it) }
        currentStepIndex = 0
        stepsPanel.revalidate(); stepsPanel.repaint()
    }

    fun addStep(step: StepRow) {
        steps.add(step)
        stepsPanel.add(step.label.apply { border = BorderFactory.createEmptyBorder(2, 0, 2, 0) })
        refreshStepLabels()
    }

    fun setStepDone(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val dim = AppColors.textSecondary.toHtmlColor()
            step.label.text =
                "<html>✅ ${step.description} <span style='color:$dim;font-size:11px'>${step.files}</span></html>"
        }
        currentStepIndex = index + 1
        refreshStepLabels()
    }

    fun setStepExecuting(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val dim = AppColors.textSecondary.toHtmlColor()
            val highlight = AppColors.tagBg.toHtmlColor()
            step.label.text =
                "<html><span style='background:$highlight;padding:2px 4px'>⏳ ${step.description}</span> <span style='color:$dim;font-size:11px'>${step.files}</span></html>"
        }
        currentStepIndex = index
    }

    fun setStepError(index: Int, msg: String) {
        steps.getOrNull(index)?.let { step ->
            val err = AppColors.error.toHtmlColor()
            step.label.text =
                "<html><span style='color:$err'>❌ ${step.description}: $msg</span></html>"
        }
    }

    fun setStepSkipped(index: Int) {
        steps.getOrNull(index)?.let { step ->
            val dim = AppColors.textSecondary.toHtmlColor()
            step.label.text =
                "<html><span style='color:$dim;text-decoration:line-through'>⏭ ${step.description}</span></html>"
        }
    }

    private fun refreshStepLabels() {
        steps.forEachIndexed { i, step ->
            if (i < currentStepIndex && step.label.text?.contains("✅") != true && step.label.text?.contains(
                    "❌"
                ) != true
            ) {
                val dim = AppColors.textSecondary.toHtmlColor()
                step.label.text =
                    "<html>✅ ${step.description} <span style='color:$dim;font-size:11px'>${step.files}</span></html>"
            }
            if (i > currentStepIndex && step.label.text?.contains("⬜") != true) {
                val dim = AppColors.textSecondary.toHtmlColor()
                step.label.text =
                    "<html><span style='color:$dim'>⬜ ${step.description}</span> <span style='color:$dim;font-size:11px'>${step.files}</span></html>"
            }
        }
    }
}
