package com.aiassistant.ui.chat

import com.aiassistant.ui.RoundedBorder
import com.intellij.ui.JBColor
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
        PENDING("⏳ 等待执行", JBColor(0x6B7280, 0x9CA3AF)),
        AWAITING_APPROVAL("🔒 等待授权", JBColor(0xF59E0B, 0xFBBF24)),
        EXECUTING("🔄 执行中...", JBColor(0x3B82F6, 0x60A5FA)),
        DONE("✅ 完成", JBColor(0x22C55E, 0x4ADE80)),
        ERROR("❌ 错误", JBColor(0xEF4444, 0xF87171)),
        TIMEOUT("⏰ 超时", JBColor(0xF59E0B, 0xFBBF24)),
        REJECTED("🚫 已拒绝", JBColor(0x6B7280, 0x9CA3AF)),
        CANCELLED("⛔ 已取消", JBColor(0x6B7280, 0x9CA3AF)),
    }

    private var state = initialState
    private val headerLabel = JLabel()
    private val bodyPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val resultArea = JTextArea().apply {
        font = Font("Monospaced", Font.PLAIN, 13)
        isEditable = false; background = JBColor(0xF6F8FA, 0x161B22)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
    private val footerLabel = JLabel()

    private val cardBorder = JBColor(0xE5E7EB, 0x374151)
    private val cardBg = JBColor(0xFFFFFF, 0x2B2B2B)
    private val headerBg = JBColor(0xF9FAFB, 0x111827)
    private val footerFg = JBColor(0x6B7280, 0x9CA3AF)

    init {
        border = BorderFactory.createCompoundBorder(
            RoundedBorder(8, cardBorder),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        isOpaque = true
        background = cardBg

        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = headerBg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(JLabel("🔧 $toolName"), BorderLayout.WEST)
            add(headerLabel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)

        val paramsLabel =
            JLabel("<html><span style='font-family:monospace;font-size:12px;color:#374151;background:#F3F4F6;padding:4px 8px'>$params</span></html>")
        bodyPanel.add(paramsLabel)
        bodyPanel.add(resultArea)
        add(bodyPanel, BorderLayout.CENTER)

        footerLabel.font = footerLabel.font.deriveFont(11f)
        footerLabel.foreground = footerFg
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
