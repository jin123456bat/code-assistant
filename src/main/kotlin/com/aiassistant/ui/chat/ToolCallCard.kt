package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*

// 工具调用卡片 — 8 个状态 + 折叠，支持亮/暗主题

class ToolCallCard(
    val toolName: String,
    val params: String,
    initialState: ToolCallState = ToolCallState.PENDING
) : JPanel(BorderLayout()) {

    enum class ToolCallState(val label: String, val color: Color) {
        PENDING("⏳ 等待执行", AppColors.textSecondary),
        AWAITING_APPROVAL("🔒 等待授权", AppColors.warning),
        EXECUTING("🔄 执行中...", AppColors.primary),
        DONE("✅ 完成", AppColors.success),
        ERROR("❌ 错误", AppColors.error),
        TIMEOUT("⏰ 超时", AppColors.warning),
        REJECTED("🚫 已拒绝", AppColors.textSecondary),
        CANCELLED("⛔ 已取消", AppColors.textSecondary),
    }

    private var state = initialState
    private val headerLabel = JLabel()
    private val bodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
    private val resultArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        isEditable = false; background = AppColors.codeBg
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
    private val footerLabel = JLabel()

    init {
        border = BorderFactory.createCompoundBorder(
            RoundedBorder(8, AppColors.border),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        isOpaque = true
        background = AppColors.cardBg

        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.headerBg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(JLabel("🔧 $toolName"), BorderLayout.WEST)
            add(headerLabel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        val fgHex = AppColors.textSecondary.toHtmlColor()
        val bgHex = AppColors.toolPlaceholderBg.toHtmlColor()
        val paramsLabel =
            JLabel("<html><span style='font-family:monospace;font-size:12px;color:$fgHex;background:$bgHex;padding:4px 8px'>$params</span></html>")
        bodyPanel.add(paramsLabel)
        bodyPanel.add(resultArea)
        add(bodyPanel, BorderLayout.CENTER)

        footerLabel.font = footerLabel.font.deriveFont(11f)
        footerLabel.foreground = AppColors.textSecondary
        footerLabel.border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        add(footerLabel, BorderLayout.SOUTH)

        setState(initialState)
    }

    fun setState(newState: ToolCallState, result: String? = null, durationMs: Long? = null) {
        state = newState
        headerLabel.foreground = newState.color
        headerLabel.text = newState.label
        if (result != null) {
            resultArea.text = result.take(2000)
            resultArea.isVisible = true
        } else {
            resultArea.isVisible = false
        }
        footerLabel.text = if (durationMs != null) "${durationMs}ms" else ""
    }

    fun setResult(result: String, durationMs: Long) {
        resultArea.text = result.take(2000)
        resultArea.isVisible = true
        footerLabel.text = "${durationMs}ms"
    }

    fun setRejected() = setState(ToolCallState.REJECTED)
    fun setCancelled() = setState(ToolCallState.CANCELLED)
}
