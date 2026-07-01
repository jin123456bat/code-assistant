package com.aiassistant.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.aiassistant.SettingsConfigurable
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 横幅（Banner）— 页面顶部持续显示直到条件消除，所有类型均支持点击关闭。
 *
 * 三种样式:
 * - INFO       (加载中/提示):  bg=#EFF6FF, fg=#3B82F6
 * - ERROR      (API 错误):    bg=#FEE2E2, fg=#EF4444
 * - ERROR_AUTH (认证失败):    bg=#FEE2E2, fg=#EF4444, [⚙ Settings]
 */
object Banner {

    enum class Type { INFO, ERROR, ERROR_AUTH }

    fun create(
        message: String,
        type: Type = Type.INFO,
        onClose: (() -> Unit)? = null,
        showSettingsButton: Boolean = false
    ): JPanel {
        val isError = type == Type.ERROR || type == Type.ERROR_AUTH
        val bgColor = if (isError) AppColors.errorBg else AppColors.primaryLight
        val fgColor = if (isError) AppColors.error else AppColors.primary
        val icon = if (type == Type.INFO) "🔄" else "⚠"

        val panel = JPanel(BorderLayout()).apply {
            background = bgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)
            )
        }

        val label = JLabel("$icon $message").apply {
            foreground = fgColor
            font = font.deriveFont(12.5f)
        }
        panel.add(label, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }

        // 所有类型：点击关闭
        if (onClose != null) {
            panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            panel.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClose()
                }
            })
            buttonPanel.add(JButton("✕").apply {
                foreground = fgColor
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { onClose() }
            })
        }

        // 错误类型额外按钮
        if (isError) {
            // ERROR_AUTH 额外显示 Settings 按钮
            if (type == Type.ERROR_AUTH && showSettingsButton) {
                val settingsBtn = JButton("⚙ Settings").apply {
                    foreground = fgColor
                    font = font.deriveFont(11f)
                    isContentAreaFilled = false
                    border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addActionListener {
                        val proj =
                            com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                        if (proj != null) {
                            ShowSettingsUtil.getInstance()
                                .showSettingsDialog(proj, SettingsConfigurable::class.java)
                        }
                    }
                }
                buttonPanel.add(settingsBtn, 0)
            }
        }
        if (buttonPanel.componentCount > 0) {
            panel.add(buttonPanel, BorderLayout.EAST)
        }

        return panel
    }
}
