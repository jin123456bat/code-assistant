package com.aiassistant.ui.page

import com.aiassistant.ApiKeyValidator
import com.aiassistant.AppSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class WelcomePage(
    private val project: Project,
    private val onApiKeySaved: () -> Unit
) : JPanel(GridBagLayout()) {

    private val settings = AppSettingsService.getInstance()
    private val apiKeyField = JBPasswordField().apply {
        columns = 30;
        val existing = settings.getApiKey(); if (existing != null) text = existing
    }
    private val statusLabel = JLabel()

    init {
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL; insets = JBUI.insets(8, 20, 8, 20); gridx = 0
        }
        gbc.gridy = 0; add(
            JBLabel(
                "<html><h1 style='text-align:center'>Welcome to Code Assistant</h1></html>",
                SwingConstants.CENTER
            ), gbc
        )
        gbc.gridy = 1; add(
            JBLabel(
                "免费 AI 编程助手 · 代码补全 + Agent 对话",
                SwingConstants.CENTER
            ).apply { foreground = JBColor(0x6B7280, 0x9CA3AF) }, gbc
        )
        gbc.gridy = 2; add(
            JBLabel("<html>① 获取 DeepSeek API Key<br><a href='https://platform.deepseek.com/api_keys'>platform.deepseek.com/api_keys</a></html>"),
            gbc
        )
        gbc.gridy = 3; add(JBLabel("② 粘贴 API Key"), gbc)
        gbc.gridy = 4; add(apiKeyField, gbc)
        gbc.gridy = 5; add(JBLabel("模型: deepseek-v4-pro（固定）").apply {
            foreground = JBColor(0x6B7280, 0x9CA3AF)
        }, gbc)

        // Save button with async validation
        gbc.gridy = 6
        val btn = JButton("保存并开始使用")
        btn.addActionListener {
            val key = String(apiKeyField.password)
            if (key.isNotBlank()) {
                settings.setApiKey(key)
                statusLabel.text = "⏳ 正在验证 API Key..."
                statusLabel.foreground = JBColor(0xF59E0B, 0xFBBF24)

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = ApiKeyValidator.validate(key)
                    SwingUtilities.invokeLater {
                        when (result) {
                            "valid" -> {
                                statusLabel.text = "✅ 验证成功"; statusLabel.foreground =
                                    JBColor(0x22C55E, 0x4ADE80); onApiKeySaved()
                            }

                            "invalid" -> {
                                statusLabel.text = "❌ API Key 无效"; statusLabel.foreground =
                                    JBColor(0xEF4444, 0xF87171)
                            }

                            else -> {
                                statusLabel.text =
                                    "⚠ 网络不可用，Key 暂未验证"; statusLabel.foreground = JBColor(
                                    0xF59E0B,
                                    0xFBBF24
                                ); Timer(1500) { onApiKeySaved() }.apply {
                                    isRepeats = false; start()
                                }
                            }
                        }
                    }
                }
            }
        }
        add(btn, gbc)

        gbc.gridy = 7; add(statusLabel, gbc)
    }
}
