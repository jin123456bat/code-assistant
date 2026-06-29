package com.aiassistant.ui

import com.intellij.openapi.options.ShowSettingsUtil
import com.aiassistant.SettingsConfigurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 横幅（Banner）— 页面顶部持续显示直到条件消除。
 *
 * 两种样式:
 * - INFO (加载中/提示):  bg=#EFF6FF, fg=#3B82F6, 无关闭按钮
 * - ERROR (错误):       bg=#FEE2E2, fg=#EF4444, [x 关闭] [⚙ Settings] 按钮
 */
object Banner {

    enum class Type { INFO, ERROR }

    fun create(
        message: String,
        type: Type = Type.INFO,
        onClose: (() -> Unit)? = null,
        showSettingsButton: Boolean = false
    ): JPanel {
        val bgColor = when (type) {
            Type.INFO -> AppColors.primaryLight
            Type.ERROR -> AppColors.errorBg
        }
        val fgColor = when (type) {
            Type.INFO -> AppColors.primary
            Type.ERROR -> AppColors.error
        }
        val icon = when (type) {
            Type.INFO -> "🔄"
            Type.ERROR -> "⚠"
        }

        val panel = JPanel(BorderLayout()).apply {
            background = bgColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)
            )
        }

        val label = JLabel("$icon $message")
        label.foreground = fgColor
        label.font = label.font.deriveFont(12.5f)
        panel.add(label, BorderLayout.CENTER)

        // 错误 Banner 需要关闭按钮和 Settings 按钮
        if (type == Type.ERROR) {
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
            }

            if (showSettingsButton) {
                val settingsBtn = JButton("⚙ Settings")
                settingsBtn.foreground = fgColor
                settingsBtn.font = settingsBtn.font.deriveFont(11f)
                settingsBtn.isContentAreaFilled = false
                settingsBtn.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                settingsBtn.cursor =
                    java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                settingsBtn.addActionListener {
                    val proj =
                        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    if (proj != null) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(proj, SettingsConfigurable::class.java)
                    }
                }
                buttonPanel.add(settingsBtn)
            }

            val closeBtn = JButton("✕")
            closeBtn.foreground = fgColor
            closeBtn.font = closeBtn.font.deriveFont(java.awt.Font.BOLD, 14f)
            closeBtn.isContentAreaFilled = false
            closeBtn.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            closeBtn.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            closeBtn.addActionListener {
                onClose?.invoke()
            }
            buttonPanel.add(closeBtn)
            panel.add(buttonPanel, BorderLayout.EAST)
        }

        return panel
    }
}
