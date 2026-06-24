package com.aiassistant

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class SettingsConfigurable : Configurable {

    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val modelCombo = JComboBox(AppSettingsService.AVAILABLE_MODELS.map { it.second }.toTypedArray())
    private val statusLabel = JBLabel()
    private val completionEnabledCheckBox = JBCheckBox("启用 AI 代码补全").apply { isSelected = true }
    private val promptArea = JTextArea(8, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val mainPanel = JPanel(BorderLayout())

    override fun createComponent(): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(20)
        }

        // API Key
        content.add(JLabel("DeepSeek API Key:"))
        content.add(apiKeyField)
        content.add(Box.createVerticalStrut(12))

        // Model
        content.add(JLabel("Model:"))
        content.add(modelCombo)
        content.add(Box.createVerticalStrut(12))

        // Completion
        completionEnabledCheckBox.addActionListener {
            AppSettingsService.getInstance()
                .setCompletionEnabled(completionEnabledCheckBox.isSelected)
        }
        content.add(completionEnabledCheckBox)
        content.add(Box.createVerticalStrut(16))

        // Commit Prompt
        content.add(JLabel("Commit Message Prompt ({diff} = git diff):"))
        content.add(JBScrollPane(promptArea).apply { preferredSize = Dimension(0, 120) })
        content.add(Box.createVerticalStrut(16))

        // Status
        statusLabel.foreground = com.intellij.ui.JBColor(0x666666, 0x8C8C8C)
        content.add(statusLabel)

        mainPanel.add(content, BorderLayout.NORTH)
        return mainPanel
    }

    override fun isModified(): Boolean {
        val s = AppSettingsService.getInstance()
        return apiKeyField.password?.concatToString() != s.getApiKey()
                || modelCombo.selectedItem != s.getModelDisplayName()
                || completionEnabledCheckBox.isSelected != s.isCompletionEnabled()
                || promptArea.text != s.getPrompt()
    }

    override fun apply() {
        val s = AppSettingsService.getInstance()
        s.setApiKey(apiKeyField.password?.concatToString() ?: "")
        val selectedIdx = modelCombo.selectedIndex
        if (selectedIdx >= 0 && selectedIdx < AppSettingsService.AVAILABLE_MODELS.size) {
            s.setModel(AppSettingsService.AVAILABLE_MODELS[selectedIdx].first)
        }
        s.setCompletionEnabled(completionEnabledCheckBox.isSelected)
        s.setPrompt(promptArea.text)
        statusLabel.text = "设置已保存"
    }

    override fun reset() {
        val s = AppSettingsService.getInstance()
        apiKeyField.text = s.getApiKey() ?: ""
        modelCombo.selectedItem = s.getModelDisplayName()
        completionEnabledCheckBox.isSelected = s.isCompletionEnabled()
        promptArea.text = s.getPrompt()
    }

    override fun getDisplayName(): String = "Code Assistant"
}
