package com.aiassistant

import com.aiassistant.ui.AppColors
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
    private val modelComboBox =
        JComboBox(AppSettingsService.AVAILABLE_MODELS.map { it.second }.toTypedArray()).apply {
            preferredSize = Dimension(200, preferredSize.height)
        }
    private val statusLabel = JBLabel()
    private val completionEnabledCheckBox = JBCheckBox("启用 AI 代码补全").apply { isSelected = true }
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(256, 1, 1024, 1)).apply {
        preferredSize = Dimension(100, preferredSize.height)
    }
    private val agentMaxLoopsSpinner = JSpinner(SpinnerNumberModel(20, 0, 9999, 1)).apply {
        preferredSize = Dimension(100, preferredSize.height)
    }
    private val agentMaxConcurrencySpinner = JSpinner(SpinnerNumberModel(3, 1, 10, 1)).apply {
        preferredSize = Dimension(100, preferredSize.height)
    }
    private val promptArea = JTextArea(8, 50).apply { lineWrap = true; wrapStyleWord = true }
    private val mainPanel = JPanel(BorderLayout())

    override fun createComponent(): JComponent {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(20)
        }

        // API Key
        content.add(JBLabel("DeepSeek API Key:"))
        content.add(apiKeyField)
        content.add(Box.createVerticalStrut(12))

        // Model 下拉选择器
        content.add(JBLabel("Model:"))
        content.add(modelComboBox)
        content.add(Box.createVerticalStrut(12))

        // Completion
        completionEnabledCheckBox.addActionListener {
            AppSettingsService.getInstance()
                .setCompletionEnabled(completionEnabledCheckBox.isSelected)
        }
        content.add(completionEnabledCheckBox)
        content.add(Box.createVerticalStrut(8))

        // Completion max_tokens（默认 256，范围 1-1024）
        val maxTokensPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        maxTokensPanel.add(JBLabel("补全 max_tokens:"))
        maxTokensPanel.add(Box.createHorizontalStrut(8))
        maxTokensPanel.add(maxTokensSpinner)
        maxTokensPanel.add(Box.createHorizontalGlue())
        content.add(maxTokensPanel)
        content.add(Box.createVerticalStrut(16))

        // Agent 最大轮次 (0=不限)
        val maxLoopsPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        maxLoopsPanel.add(JBLabel("Agent 最大轮次 (0=不限):"))
        maxLoopsPanel.add(Box.createHorizontalStrut(8))
        maxLoopsPanel.add(agentMaxLoopsSpinner)
        maxLoopsPanel.add(Box.createHorizontalGlue())
        content.add(maxLoopsPanel)
        content.add(Box.createVerticalStrut(8))

        // 多 Agent 并发上限
        val maxConcurrencyPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        maxConcurrencyPanel.add(JBLabel("多 Agent 并发上限:"))
        maxConcurrencyPanel.add(Box.createHorizontalStrut(8))
        maxConcurrencyPanel.add(agentMaxConcurrencySpinner)
        maxConcurrencyPanel.add(Box.createHorizontalGlue())
        content.add(maxConcurrencyPanel)
        content.add(Box.createVerticalStrut(16))

        // Commit Prompt
        content.add(JBLabel("Commit Message Prompt ({diff} = git diff):"))
        content.add(JBScrollPane(promptArea).apply { preferredSize = Dimension(0, 120) })
        content.add(Box.createVerticalStrut(16))

        // Status
        statusLabel.foreground = AppColors.textSecondary
        content.add(statusLabel)

        mainPanel.add(content, BorderLayout.NORTH)
        return mainPanel
    }

    override fun isModified(): Boolean {
        val s = AppSettingsService.getInstance()
        return apiKeyField.password?.concatToString() != s.getApiKey()
                || getSelectedModelId() != s.getModel()
                || completionEnabledCheckBox.isSelected != s.isCompletionEnabled()
                || (maxTokensSpinner.value as Int) != s.getCompletionMaxTokens()
                || (agentMaxLoopsSpinner.value as Int) != s.getAgentMaxLoops()
                || (agentMaxConcurrencySpinner.value as Int) != s.getAgentMaxConcurrency()
                || promptArea.text != s.getPrompt()
    }

    override fun apply() {
        val s = AppSettingsService.getInstance()
        s.setApiKey(apiKeyField.password?.concatToString() ?: "")
        s.setModel(getSelectedModelId())
        s.setCompletionEnabled(completionEnabledCheckBox.isSelected)
        s.setCompletionMaxTokens(maxTokensSpinner.value as Int)
        s.setAgentMaxLoops(agentMaxLoopsSpinner.value as Int)
        s.setAgentMaxConcurrency(agentMaxConcurrencySpinner.value as Int)
        s.setPrompt(promptArea.text)
        statusLabel.text = "设置已保存"
    }

    override fun reset() {
        val s = AppSettingsService.getInstance()
        apiKeyField.text = s.getApiKey() ?: ""
        modelComboBox.selectedItem = s.getModelDisplayName()
        completionEnabledCheckBox.isSelected = s.isCompletionEnabled()
        maxTokensSpinner.value = s.getCompletionMaxTokens()
        agentMaxLoopsSpinner.value = s.getAgentMaxLoops()
        agentMaxConcurrencySpinner.value = s.getAgentMaxConcurrency()
        promptArea.text = s.getPrompt()
    }

    private fun getSelectedModelId(): String {
        val selectedDisplayName = modelComboBox.selectedItem as? String
            ?: return AppSettingsService.AVAILABLE_MODELS.first().first
        return AppSettingsService.AVAILABLE_MODELS.firstOrNull { it.second == selectedDisplayName }?.first
            ?: AppSettingsService.AVAILABLE_MODELS.first().first
    }

    override fun getDisplayName(): String = "Code Assistant"
}
