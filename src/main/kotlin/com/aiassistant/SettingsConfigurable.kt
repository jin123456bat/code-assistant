package com.aiassistant

import com.aiassistant.completion.CompletionStats
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
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
    private val compactRatioSpinner = JSpinner(SpinnerNumberModel(90, 10, 100, 5))
    // ---- whitelist ----
    private val toolWhitelistPanel = JPanel()
    private val commandWhitelistPanel = JPanel()
    init {
        toolWhitelistPanel.layout = BoxLayout(toolWhitelistPanel, BoxLayout.Y_AXIS)
        commandWhitelistPanel.layout = BoxLayout(commandWhitelistPanel, BoxLayout.Y_AXIS)
    }
    // ---- 补全设置 ----
    private val completionEnabledCheckBox = JBCheckBox("启用 AI 代码补全").apply { isSelected = true }
    private val completionMaxTokensSpinner = JSpinner(SpinnerNumberModel(1024, 1, 1024, 1))
    private val completionDebounceSpinner = JSpinner(SpinnerNumberModel(300, 100, 2000, 100))
    private val completionNumCandidatesSpinner = JSpinner(SpinnerNumberModel(10, 1, 10, 1))
    private val completionStatsLabel = JBLabel().apply {
        foreground = JBColor(0x666666, 0x8C8C8C)
    }
    private val mainPanel = JPanel(BorderLayout())

    private fun refreshWhitelistUI() {
        val service = AppSettingsService.getInstance()
        toolWhitelistPanel.removeAll()
        val tools = service.getToolWhitelist().sorted()
        if (tools.isEmpty()) {
            toolWhitelistPanel.add(JLabel("  ${AiAssistantBundle.message("settings.whitelist.empty")}").apply {
                foreground = JBColor(0x999999, 0x777777)
            })
        } else {
            for (tool in tools) {
                val row = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }
                row.add(JLabel("  $tool"))
                row.add(Box.createHorizontalGlue())
                val removeBtn = JButton(AiAssistantBundle.message("settings.whitelist.remove")).apply {
                    addActionListener {
                        service.removeToolFromWhitelist(tool)
                        refreshWhitelistUI()
                    }
                }
                row.add(removeBtn)
                toolWhitelistPanel.add(row)
            }
        }
        toolWhitelistPanel.revalidate()
        toolWhitelistPanel.repaint()

        commandWhitelistPanel.removeAll()
        val commands = service.getCommandWhitelist().sorted()
        if (commands.isEmpty()) {
            commandWhitelistPanel.add(JLabel("  ${AiAssistantBundle.message("settings.whitelist.empty")}").apply {
                foreground = JBColor(0x999999, 0x777777)
            })
        } else {
            for (cmd in commands) {
                val row = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }
                row.add(JLabel("  $cmd"))
                row.add(Box.createHorizontalGlue())
                val removeBtn = JButton(AiAssistantBundle.message("settings.whitelist.remove")).apply {
                    addActionListener {
                        service.removeCommandFromWhitelist(cmd)
                        refreshWhitelistUI()
                    }
                }
                row.add(removeBtn)
                commandWhitelistPanel.add(row)
            }
        }
        commandWhitelistPanel.revalidate()
        commandWhitelistPanel.repaint()
    }

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

        // Auto-compact ratio
        gbc.gridy = 5; gbc.gridx = 0; gbc.weightx = 0.0; gbc.gridwidth = 1
        contentPanel.add(JLabel("自动 Compact 触发比例"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        contentPanel.add(compactRatioSpinner, gbc)
        gbc.gridx = 2; gbc.weightx = 0.0
        contentPanel.add(JLabel("% 上下文窗口"), gbc)

        // Prompt label
        gbc.gridy = 6; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(12, 8, 4, 8)
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.prompt.label")), gbc)
        gbc.insets = JBUI.insets(6, 8)

        // Prompt editor
        gbc.gridy = 7; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.BOTH
        contentPanel.add(promptScrollPane, gbc)

        // Reset button
        gbc.gridy = 8; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        val resetBtn = JButton(AiAssistantBundle.message("settings.prompt.reset"))
        resetBtn.addActionListener { promptArea.text = AppSettingsService.DEFAULT_COMMIT_PROMPT_ZH }
        contentPanel.add(resetBtn, gbc)

        // ---- Whitelist section ----
        gbc.gridy = 9; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(16, 8, 4, 8)
        contentPanel.add(JLabel("<html><b>${AiAssistantBundle.message("settings.whitelist.header")}</b></html>"), gbc)
        gbc.gridy = 10; gbc.insets = JBUI.insets(2, 8, 2, 8)
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.whitelist.desc")).apply {
            foreground = JBColor(0x666666, 0x8C8C8C)
        }, gbc)

        gbc.gridy = 11; gbc.insets = JBUI.insets(4, 16, 2, 8)
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.whitelist.tool")), gbc)
        gbc.gridy = 12; gbc.insets = JBUI.insets(0, 24, 4, 8)
        contentPanel.add(toolWhitelistPanel, gbc)

        gbc.gridy = 13; gbc.insets = JBUI.insets(4, 16, 2, 8)
        contentPanel.add(JLabel(AiAssistantBundle.message("settings.whitelist.command")), gbc)
        gbc.gridy = 14; gbc.insets = JBUI.insets(0, 24, 4, 8)
        contentPanel.add(commandWhitelistPanel, gbc)

        // Status（放在 whitelist 之后，避免被覆盖）
        gbc.gridy = 15; gbc.gridx = 0; gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(8, 8, 2, 8)
        contentPanel.add(statusLabel, gbc)

        // ---- 补全设置 Section ----
        gbc.gridy = 16; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = JBUI.insets(16, 8, 4, 8)
        contentPanel.add(
            JBLabel("<html><b>代码补全</b></html>"), gbc
        )

        gbc.gridy = 17; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionEnabledCheckBox, gbc)

        gbc.gridy = 18; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("最大补全长度 (tokens)"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionMaxTokensSpinner, gbc)

        gbc.gridy = 19; gbc.gridx = 0; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("防抖延迟 (ms)"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionDebounceSpinner, gbc)

        gbc.gridy = 20; gbc.gridx = 0; gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        contentPanel.add(JLabel("候选数量"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        gbc.insets = JBUI.insets(4, 8, 4, 8)
        contentPanel.add(completionNumCandidatesSpinner, gbc)

        // ---- 统计卡片 ----
        gbc.gridy = 21; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(12, 16, 4, 8)
        contentPanel.add(JLabel("<html><b>补全统计</b></html>"), gbc)

        gbc.gridy = 22; gbc.insets = JBUI.insets(2, 16, 4, 8)
        contentPanel.add(completionStatsLabel, gbc)

        gbc.gridy = 23; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.insets = JBUI.insets(4, 16, 4, 8)
        gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.WEST
        val resetStatsBtn = JButton("重置统计")
        resetStatsBtn.addActionListener {
            CompletionStats.reset()
            refreshCompletionStatsUI()
        }
        contentPanel.add(resetStatsBtn, gbc)

        // Filler
        gbc.gridy = 24; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        gbc.anchor = GridBagConstraints.NORTHWEST
        contentPanel.add(JPanel(), gbc)

        mainPanel.add(contentPanel, BorderLayout.NORTH)
        refreshWhitelistUI()
        return mainPanel
    }

    private var cachedApiKey: String? = null

    override fun isModified(): Boolean {
        val service = AppSettingsService.getInstance()
        val savedApiKey = service.getApiKey() ?: ""
        val inputApiKey = String(apiKeyField.password)
        if (savedApiKey != inputApiKey) return true

        val savedPrompt = service.getEffectivePrompt()
        val inputPrompt = promptArea.text.trim()
        if (savedPrompt != inputPrompt) return true

        val savedModel = service.getModel()
        val idx = modelCombo.selectedIndex
        val selectedModel = if (idx >= 0) AppSettingsService.AVAILABLE_MODELS[idx].first else ""
        if (savedModel != selectedModel) return true

        val savedRatio = (service.getCompactRatio() * 100).toInt()
        if (savedRatio != (compactRatioSpinner.value as Int)) return true

        val svc = AppSettingsService.getInstance()
        if (svc.isCompletionEnabled() != completionEnabledCheckBox.isSelected) return true
        if (svc.getCompletionMaxTokens() != (completionMaxTokensSpinner.value as Int)) return true
        if (svc.getCompletionDebounceMs() != (completionDebounceSpinner.value as Int)) return true
        if (svc.getCompletionNumCandidates() != (completionNumCandidatesSpinner.value as Int)) return true

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

        val idx = modelCombo.selectedIndex
        if (idx >= 0) {
            val model = AppSettingsService.AVAILABLE_MODELS[idx].first
            service.setModel(model)
        }

        service.setCompactRatio((compactRatioSpinner.value as Int) / 100.0)

        val svc = AppSettingsService.getInstance()
        svc.setCompletionEnabled(completionEnabledCheckBox.isSelected)
        svc.setCompletionMaxTokens(completionMaxTokensSpinner.value as Int)
        svc.setCompletionDebounceMs(completionDebounceSpinner.value as Int)
        svc.setCompletionNumCandidates(completionNumCandidatesSpinner.value as Int)

        statusLabel.text = AiAssistantBundle.message("settings.key.saved")
        statusLabel.foreground = JBColor(0x1B5E20, 0x80C080)
        ChatToolWindow.notifySettingsChanged()
    }

    override fun reset() {
        val service = AppSettingsService.getInstance()
        cachedApiKey = service.getApiKey() ?: ""
        apiKeyField.text = cachedApiKey
        promptArea.text = service.getEffectivePrompt()
        val savedModel = service.getModel()
        val idx = AppSettingsService.AVAILABLE_MODELS.indexOfFirst { it.first == savedModel }
        if (idx >= 0) modelCombo.selectedIndex = idx
        compactRatioSpinner.value = (service.getCompactRatio() * 100).toInt()
        statusLabel.text = ""
        val service1 = AppSettingsService.getInstance()
        completionEnabledCheckBox.isSelected = service1.isCompletionEnabled()
        completionMaxTokensSpinner.value = service1.getCompletionMaxTokens()
        completionDebounceSpinner.value = service1.getCompletionDebounceMs()
        completionNumCandidatesSpinner.value = service1.getCompletionNumCandidates()
        refreshCompletionStatsUI()
        refreshWhitelistUI()
    }

    private fun refreshCompletionStatsUI() {
        val stats = CompletionStats
        val snap = stats.getSnapshot()
        val acceptRate = "%.1f".format(stats.getAcceptRate())

        val sb = StringBuilder()
        sb.appendLine("显示: ${snap.shown}   接受: ${snap.accepted}   接受率: ${acceptRate}%")
        sb.appendLine("平均延迟: ${stats.getAverageLatencyMs()}ms")

        if (snap.byLanguage.isNotEmpty()) {
            sb.appendLine()
            for ((lang, ls) in snap.byLanguage.entries.sortedBy { it.key }) {
                val langRate = if (ls.shown > 0) "%.1f".format(ls.accepted.toDouble() / ls.shown * 100.0) else "0.0"
                sb.appendLine("$lang: ${ls.accepted}/${ls.shown} ($langRate%)  ${ls.avgLatencyMs}ms")
            }
        }

        completionStatsLabel.text = sb.toString()
    }
}
