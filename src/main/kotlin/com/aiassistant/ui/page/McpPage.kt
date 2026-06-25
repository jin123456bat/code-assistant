package com.aiassistant.ui.page

import com.aiassistant.mcp.McpManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class McpPage(project: Project) : JPanel(BorderLayout()) {

    private val manager = McpManager(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val addForm = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        // Header with add button
        val header = JPanel(FlowLayout(FlowLayout.LEFT))
        header.add(JLabel("<html><b style='font-size:16px'>🔌 MCP Servers</b></html>"))
        header.add(JButton("➕ 添加").apply {
            addActionListener { addForm.isVisible = !addForm.isVisible }
        })

        // Add form
        addForm.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AppColors.border),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        )
        addForm.isVisible = false
        val nameField = JTextField(15)
        val cmdField = JTextField(25)
        addForm.add(JLabel("名称")); addForm.add(nameField)
        addForm.add(JLabel("命令")); addForm.add(cmdField)
        val saveBtn = JButton("保存").apply {
            addActionListener {
                val config = McpManager.McpServerConfig(
                    id = nameField.text.ifBlank { "server-${System.currentTimeMillis()}" },
                    command = cmdField.text
                )
                if (cmdField.text.isNotBlank()) {
                    manager.addServer(config)
                    nameField.text = ""; cmdField.text = ""
                    addForm.isVisible = false
                    refreshList()
                }
            }
        }
        addForm.add(saveBtn)

        // Top panel: header + add form
        val topPanel = JPanel(BorderLayout())
        topPanel.add(header, BorderLayout.NORTH)
        topPanel.add(addForm, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)
        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // Footer
        val footerHex = AppColors.textSecondary.toHtmlColor()
        add(
            JLabel("<html><span style='color:$footerHex;font-size:11px'>配置文件: .code-assistant/mcp-config.json · 兼容 .mcp.json · ~/.claude/.mcp.json</span></html>"),
            BorderLayout.SOUTH
        )

        refreshList()
    }

    fun refreshList() {
        listContainer.removeAll()
        val servers = manager.loadServers()
        if (servers.isEmpty()) {
            listContainer.add(renderEmpty())
        } else {
            servers.forEach { listContainer.add(renderCard(it)) }
        }
        listContainer.revalidate(); listContainer.repaint()
    }

    private fun renderCard(server: McpManager.McpServer): JPanel {
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            isOpaque = true
            background = AppColors.cardBg
        }
        val greenHex = AppColors.success.toHtmlColor()
        val amberHex = AppColors.warning.toHtmlColor()
        val redHex = AppColors.error.toHtmlColor()
        val dimHex = AppColors.textSecondary.toHtmlColor()

        // 状态指示灯 + 名称 + 状态
        val (dotColor, stateLabel) = when (server.state) {
            McpManager.State.RUNNING -> greenHex to "RUNNING"
            McpManager.State.INITIALIZING -> amberHex to "INITIALIZING"
            McpManager.State.CONFIGURED -> amberHex to "CONFIGURED"
            McpManager.State.CRASHED -> redHex to "CRASHED"
            McpManager.State.ERROR -> redHex to "ERROR"
            else -> dimHex to "${server.state}"
        }
        val info = JLabel(
            "<html>" +
                    "<span style='display:inline-block;width:8px;height:8px;border-radius:50%;background:$dotColor;margin-right:6px'>&nbsp;</span>" +
                    "<b>${server.config.id}</b>" +
                    " <span style='color:$dotColor;font-size:11px'>$stateLabel</span>" +
                    "<br><span style='color:$dimHex;font-size:11px'>command: ${server.config.command}</span>" +
                    "<br><span style='color:$dimHex;font-size:11px'>tools: ${
                        server.tools.joinToString(
                            ", "
                        ).ifEmpty { "(none)" }
                    } (${server.tools.size})</span>" +
                    "</html>"
        )
        card.add(info, BorderLayout.CENTER)

        // 操作按钮
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        actions.add(JButton("🔄 重连").apply {
            font = font.deriveFont(11f)
            addActionListener { /* ponytail: reconnect later */ }
        })
        actions.add(JButton("✏ 编辑").apply {
            font = font.deriveFont(11f)
            addActionListener { /* ponytail: edit later */ }
        })
        actions.add(JButton("🗑 删除").apply {
            font = font.deriveFont(11f)
            foreground = AppColors.error
            addActionListener { manager.removeServer(server.config.id); refreshList() }
        })
        card.add(actions, BorderLayout.EAST)
        return card
    }

    private fun renderEmpty(): JPanel {
        val dimHex = AppColors.textSecondary.toHtmlColor()
        return JPanel().apply {
            add(JLabel("<html><div style='text-align:center;padding:40px;color:$dimHex'>还没有 MCP Server<br><span style='font-size:11px'>添加 MCP Server 连接外部工具</span></div></html>"))
        }
    }
}
