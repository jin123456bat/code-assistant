package com.aiassistant.ui.page

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.options.ShowSettingsUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

class SettingsPage : JPanel(BorderLayout()) {

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            background = AppColors.pageBg
        }

        content.add(
            card(
                "关于",
                "Code Assistant v2.0.0",
                "JetBrains IDE 内的代码助手，支持 Agent、补全和 Git message 生成。"
            )
        )
        content.add(Box.createVerticalStrut(10))
        content.add(
            card(
                "快捷键参考",
                "打开面板: Ctrl+Shift+K / Cmd+Shift+K    发送消息: Enter（换行: Shift+Enter）    关闭 Popup: Escape    新建会话: Ctrl+Shift+N / Cmd+Shift+N",
                ""
            )
        )
        content.add(Box.createVerticalStrut(10))
        content.add(settingsEntryCard())
        content.add(Box.createVerticalGlue())

        add(JScrollPane(content).apply { border = null }, BorderLayout.CENTER)
    }

    private fun settingsEntryCard(): JPanel {
        val card = card(
            "IDE Settings",
            "Agent 设置已迁移到 IDE Settings > Tools > Code Assistant",
            "API Key、模型和补全参数在 IDE 设置页统一管理。"
        )
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(JButton("打开 IDE 设置").apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(null, "Code Assistant")
                }
            })
        }
        card.add(buttonRow, BorderLayout.SOUTH)
        return card
    }

    private fun card(title: String, primary: String, secondary: String): JPanel {
        val dimHex = AppColors.textSecondary.toHtmlColor()
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AppColors.border),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
            )
            background = AppColors.cardBg
            maximumSize = Dimension(Int.MAX_VALUE, 120)
            add(
                JLabel(
                    "<html><b>$title</b><br><br>$primary<br><span style='color:$dimHex;font-size:11px'>$secondary</span></html>"
                ),
                BorderLayout.CENTER
            )
        }
    }
}
