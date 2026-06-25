package com.aiassistant.ui.page

import com.aiassistant.ApiKeyValidator
import com.aiassistant.AppSettingsService
import com.aiassistant.ui.AppColors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
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
            JLabel(
                "<html><h1 style='text-align:center'>Welcome to Code Assistant</h1></html>",
                SwingConstants.CENTER
            ), gbc
        )
        gbc.gridy = 1; add(
            JLabel(
                "免费 AI 编程助手 · 代码补全 + Agent 对话",
                SwingConstants.CENTER
            ).apply { foreground = AppColors.textSecondary }, gbc
        )
        gbc.gridy = 2; add(
            JLabel("<html>① 获取 DeepSeek API Key<br><a href='https://platform.deepseek.com/api_keys'>platform.deepseek.com/api_keys</a></html>"),
            gbc
        )
        gbc.gridy = 3; add(JLabel("② 粘贴 API Key"), gbc)
        gbc.gridy = 4; add(apiKeyField, gbc)
        gbc.gridy = 5; add(JLabel("模型: deepseek-v4-pro（固定）").apply {
            foreground = AppColors.textSecondary
        }, gbc)

        // Save button with async validation
        gbc.gridy = 6
        val btn = JButton("保存并开始使用")
        btn.addActionListener {
            val key = String(apiKeyField.password)
            if (key.isNotBlank()) {
                settings.setApiKey(key)
                // 乐观导航：保存 Key 后立即跳转 Chat，验证在后台继续
                onApiKeySaved()

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = ApiKeyValidator.validate(key)
                    SwingUtilities.invokeLater {
                        when (result) {
                            "valid" -> statusLabel.text = "" // 静默通过
                            "invalid" -> {
                                statusLabel.text = "❌ API Key 无效，请检查 Settings 页面"
                                statusLabel.foreground = AppColors.error
                            }
                            else -> {
                                statusLabel.text = "⚠ 网络不可用，Key 暂未验证"
                                statusLabel.foreground = AppColors.warning
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
