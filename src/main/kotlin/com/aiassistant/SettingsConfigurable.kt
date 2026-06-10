package com.aiassistant

import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class SettingsConfigurable : Configurable {

    private val apiKeyField = JBPasswordField().apply { columns = 40 }
    private val promptArea = JTextArea(8, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = AiAssistantBundle.message("settings.prompt.placeholder")
    }
    private val promptScrollPane = JBScrollPane(promptArea).apply {
        preferredSize = Dimension(0, 150)
    }
    private val endpointLabel = JLabel(AnthropicAdapter.DEFAULT_ENDPOINT)
    private val modelCombo = JComboBox(AppSettingsService.AVAILABLE_MODELS.map { it.second }.toTypedArray())
    private val statusLabel = JBLabel().apply {
        foreground = JBColor(0x666666, 0x8C8C8C)
    }
    private val mainPanel = JPanel(BorderLayout())

    override fun getDisplayName(): String = AiAssistantBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val contentPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(6, 8)
            anchor = GridBagConstraints.NORTHWEST
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        contentPanel.add(
            JBLabel("<html><h3 style='margin:0'>" + AiAssistantBundle.message("settings.header") + "</h3></html>"),
            gbc
        )

        gbc.gridy = 1
        contentPanel.add(JBLabel(AiAssistantBundle.message("settings.help")), gbc)

        gbc.gridwidth = 1

        // API Key
        gbc.gridy = 2; gbc.gridx = 0; gbc.weightx = 0.0
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.api.key")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        contentPanel.add(apiKeyField, gbc)

        // Endpoint (read-only)
        gbc.gridy = 3; gbc.gridx = 0; gbc.weightx = 0.0
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.endpoint")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        endpointLabel.foreground = JBColor(0x999999, 0x777777)
        contentPanel.add(endpointLabel, gbc)

        // Model (dropdown)
        gbc.gridy = 4; gbc.gridx = 0; gbc.weightx = 0.0
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.model")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        contentPanel.add(modelCombo, gbc)

        // Prompt label
        gbc.gridy = 5; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(12, 8, 4, 8)
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.prompt.label")), gbc)
        gbc.insets = JBUI.insets(6, 8)

        // Prompt editor
        gbc.gridy = 6; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH
        contentPanel.add(promptScrollPane, gbc)

        // Reset button
        gbc.gridy = 7; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        val resetBtn = JButton(AiAssistantBundle.message("settings.prompt.reset"))
        resetBtn.addActionListener { promptArea.text = "" }
        contentPanel.add(resetBtn, gbc)

        // Status
        gbc.gridy = 8; gbc.gridx = 0; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        contentPanel.add(statusLabel, gbc)

        // Filler
        gbc.gridy = 9; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        contentPanel.add(JPanel(), gbc)

        mainPanel.add(contentPanel, BorderLayout.NORTH)
        return mainPanel
    }

    private var cachedApiKey: String? = null

    override fun isModified(): Boolean {
        val service = AppSettingsService.getInstance()
        val savedApiKey = cachedApiKey ?: ""
        val inputApiKey = String(apiKeyField.password)
        if (savedApiKey != inputApiKey) return true

        val savedPrompt = service.getPrompt() ?: ""
        val inputPrompt = promptArea.text.trim()
        if (savedPrompt != inputPrompt) return true

        val savedModel = service.getModel()
        val selectedModel = AppSettingsService.AVAILABLE_MODELS[modelCombo.selectedIndex].first
        if (savedModel != selectedModel) return true

        return false
    }

    override fun apply() {
        val service = AppSettingsService.getInstance()

        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isEmpty()) {
            service.clearApiKey()
            cachedApiKey = null
            AppLogger.settingsCleared()
        } else if (apiKey.length < 8) {
            statusLabel.text = AiAssistantBundle.message("settings.key.tooshort")
            statusLabel.foreground = JBColor(0xB00020, 0xFF8080)
            return
        } else {
            service.setApiKey(apiKey)
            cachedApiKey = apiKey
            AppLogger.settingsSaved(apiKey.length)
        }

        val prompt = promptArea.text.trim()
        service.setPrompt(prompt.ifBlank { null })

        val model = AppSettingsService.AVAILABLE_MODELS[modelCombo.selectedIndex].first
        service.setModel(model)

        statusLabel.text = AiAssistantBundle.message("settings.key.saved")
        statusLabel.foreground = JBColor(0x1B5E20, 0x80C080)
        ChatToolWindow.notifySettingsChanged()
    }

    override fun reset() {
        val service = AppSettingsService.getInstance()
        cachedApiKey = service.getApiKey() ?: ""
        apiKeyField.text = cachedApiKey
        promptArea.text = service.getPrompt() ?: ""
        val savedModel = service.getModel()
        val idx = AppSettingsService.AVAILABLE_MODELS.indexOfFirst { it.first == savedModel }
        if (idx >= 0) modelCombo.selectedIndex = idx
        statusLabel.text = ""
    }
}
