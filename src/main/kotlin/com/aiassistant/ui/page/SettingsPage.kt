package com.aiassistant.ui.page

import com.aiassistant.AppSettingsService
import com.intellij.ui.JBColor
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class SettingsPage : JPanel(BorderLayout()) {

    private val settings = AppSettingsService.getInstance()
    private val feedbackLabel = JLabel(" ")

    init {
        val form = JPanel(GridBagLayout()).apply { border = EmptyBorder(16, 16, 16, 16) }
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
                    feedbackLabel.foreground = JBColor(0x22C55E, 0x4ADE80)
                    // 3秒后清除反馈
                    Timer(3000) { feedbackLabel.text = " " }.apply { isRepeats = false; start() }
                }
            }
        }, gbc)
        gbc.gridy++; form.add(feedbackLabel, gbc)
        gbc.gridy++; form.add(JLabel("状态: 已配置"), gbc)

        addSection(form, gbc, "Agent")
        val timeoutField = JTextField("0", 5)
        addRow(form, gbc, "Shell 超时上限 (秒, 0=不限)", timeoutField)
        val turnsField = JTextField("0", 5)  // 0 = 不设硬限制，自然终止
        addRow(form, gbc, "最大轮次 (0=不限)", turnsField)
        val concurrencyField = JTextField("3", 5)
        addRow(form, gbc, "多 Agent 并发上限", concurrencyField)

        addSection(form, gbc, "快捷键")
        gbc.gridy++; form.add(
            JLabel("<html>打开面板: <b>Ctrl+Shift+K</b> (Mac: <b>⌘⇧K</b>)</html>"),
            gbc
        )
        gbc.gridy++; form.add(
            JLabel("<html>发送消息: <b>Enter</b> (换行: <b>Shift+Enter</b>)</html>"),
            gbc
        )
        gbc.gridy++; form.add(JLabel("停止生成: <b>Escape</b>"), gbc)
        gbc.gridy++; form.add(
            JLabel("<html>新建会话: <b>Ctrl+Shift+N</b> (Mac: <b>⌘⇧N</b>)</html>"),
            gbc
        )

        addSection(form, gbc, "关于")
        gbc.gridy++; form.add(JLabel("Code Assistant v2.0.0"), gbc)
        gbc.gridy++; form.add(
            JLabel("<html><a href='https://github.com/jincc/code-assistant'>github.com/jincc/code-assistant</a></html>"),
            gbc
        )

        gbc.gridy++; gbc.weighty = 1.0; form.add(JPanel(), gbc)
        add(JScrollPane(form).apply { border = null }, BorderLayout.CENTER)
    }

    private fun addSection(form: JPanel, gbc: GridBagConstraints, title: String) {
        gbc.gridy++; gbc.insets = Insets(12, 8, 4, 8)
        form.add(JSeparator(), gbc)
        gbc.gridy++; form.add(JLabel("<html><b>$title</b></html>"), gbc)
        gbc.insets = Insets(4, 8, 4, 8)
    }

    private fun addRow(form: JPanel, gbc: GridBagConstraints, label: String, field: JComponent) {
        gbc.gridy++; form.add(JLabel(label), gbc)
        gbc.gridy++; form.add(field, gbc)
    }
}
