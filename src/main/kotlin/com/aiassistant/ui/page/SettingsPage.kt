package com.aiassistant.ui.page

import com.aiassistant.AppSettingsService
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class SettingsPage : JPanel(BorderLayout()) {

    private val settings = AppSettingsService.getInstance()
    private val feedbackLabel = JLabel(" ")

    init {
        val form = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(16, 16, 16, 16)
            isOpaque = true
            background = AppColors.pageBg
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
            insets = Insets(4, 8, 4, 8); gridx = 0; weightx = 1.0
        }

        addSection(form, gbc, "API")
        val keyField = JPasswordField(settings.getApiKey() ?: "", 30)
        addRow(form, gbc, "API Key", keyField)
        gbc.gridy++; form.add(JButton("保存").apply {
            addActionListener {
                val k = String(keyField.password)
                if (k.isNotBlank()) {
                    settings.setApiKey(k)
                    feedbackLabel.text = "✅ API Key 已保存"
                    feedbackLabel.foreground = AppColors.success
                    // 3秒后清除反馈
                    Timer(3000) { feedbackLabel.text = " " }.apply { isRepeats = false; start() }
                }
            }
        }, gbc)
        gbc.gridy++; form.add(feedbackLabel, gbc)
        gbc.gridy++; form.add(JLabel("状态: 已配置").apply {
            foreground = AppColors.success
        }, gbc)

        addSection(form, gbc, "Agent")
        addSectionHint(form, gbc, "Shell 超时由 LLM 在每次工具调用时传入，无需手动配置")
        val turnsField = JTextField("0", 5)  // 0 = 不设硬限制，自然终止
        addRow(form, gbc, "最大轮次 (0=不限)", turnsField)
        val concurrencyField = JTextField("3", 5)
        addRow(form, gbc, "多 Agent 并发上限", concurrencyField)

        addSection(form, gbc, "快捷键")
        val shortcutDimHex = AppColors.textSecondary.toHtmlColor()
        gbc.gridy++; form.add(
            JLabel("<html>打开面板: <b>Ctrl+Shift+K</b> <span style='color:$shortcutDimHex'>(Mac: ⌘⇧K)</span></html>"),
            gbc
        )
        gbc.gridy++; form.add(
            JLabel("<html>发送消息: <b>Enter</b> <span style='color:$shortcutDimHex'>(换行: Shift+Enter)</span></html>"),
            gbc
        )
        gbc.gridy++; form.add(JLabel("停止生成: <b>Escape</b>"), gbc)
        gbc.gridy++; form.add(
            JLabel("<html>新建会话: <b>Ctrl+Shift+N</b> <span style='color:$shortcutDimHex'>(Mac: ⌘⇧N)</span></html>"),
            gbc
        )

        addSection(form, gbc, "关于")
        gbc.gridy++; form.add(JLabel("Code Assistant v2.0.0"), gbc)
        gbc.gridy++; form.add(
            JLabel("<html><a href='https://github.com/jin123456bat/code-assistant'>github.com/jin123456bat/code-assistant</a></html>"),
            gbc
        )

        gbc.gridy++; gbc.weighty = 1.0; form.add(JPanel().apply { isOpaque = false }, gbc)
        add(JScrollPane(form).apply { border = null }, BorderLayout.CENTER)
    }

    private fun addSection(form: JPanel, gbc: GridBagConstraints, title: String) {
        gbc.gridy++; gbc.insets = Insets(12, 8, 4, 8)
        form.add(JSeparator().apply { foreground = AppColors.border }, gbc)
        gbc.gridy++; form.add(JLabel("<html><b>$title</b></html>").apply {
            foreground = AppColors.textSecondary
        }, gbc)
        gbc.insets = Insets(4, 8, 4, 8)
    }

    private fun addSectionHint(form: JPanel, gbc: GridBagConstraints, hint: String) {
        gbc.gridy++; gbc.insets = Insets(2, 8, 2, 8)
        form.add(JLabel(hint).apply {
            font = font.deriveFont(11f)
            foreground = AppColors.textSecondary
        }, gbc)
        gbc.insets = Insets(4, 8, 4, 8)
    }

    private fun addRow(form: JPanel, gbc: GridBagConstraints, label: String, field: JComponent) {
        gbc.gridy++; form.add(JLabel(label), gbc)
        gbc.gridy++; form.add(field, gbc)
    }
}
