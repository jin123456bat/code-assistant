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
    private val nameField = JTextField(15)
    private val cmdField = JTextField(25)
    private var editingServerId: String? = null

    init {
        // Header with add button
        val header = JPanel(FlowLayout(FlowLayout.LEFT))
        header.add(JLabel("<html><b style='font-size:16px'>🔌 MCP Servers</b></html>"))
        header.add(JButton("➕ 添加").apply {
            addActionListener {
                editingServerId = null
                nameField.text = ""
                cmdField.text = ""
                addForm.isVisible = !addForm.isVisible
            }
        })

        // Add form
        addForm.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AppColors.border),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        )
        addForm.isVisible = false
        addForm.add(JLabel("名称")); addForm.add(nameField)
        addForm.add(JLabel("命令")); addForm.add(cmdField)
        val saveBtn = JButton("保存").apply {
            addActionListener {
                if (cmdField.text.isNotBlank()) {
                    val id = nameField.text.ifBlank { "server-${System.currentTimeMillis()}" }
                    val editingId = editingServerId
                    if (editingId != null) {
                        manager.updateServer(editingId) {
                            it.copy(
                                id = id,
                                command = cmdField.text
                            )
                        }
                    } else {
                        manager.addServer(
                            McpManager.McpServerConfig(
                                id = id,
                                command = cmdField.text
                            )
                        )
                    }
                    editingServerId = null
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
        // 文档 §六 定义: 🟢 RUNNING / 🟡 INITIALIZING / 🔴 CRASHED / ERROR
        val (dotColor, stateLabel) = when (server.state) {
            McpManager.State.RUNNING -> greenHex to "RUNNING"
            McpManager.State.INITIALIZING -> amberHex to "INITIALIZING"
            McpManager.State.CONFIGURED -> dimHex to "CONFIGURED"
            McpManager.State.CRASHED -> redHex to "CRASHED"
            McpManager.State.ERROR -> redHex to "ERROR"
            McpManager.State.INIT_ERROR -> redHex to "ERROR"
            else -> dimHex to "${server.state}"
        }

        // 构建卡片 HTML，包含状态指示灯、名称、状态、命令、工具列表等
        val htmlBuilder = StringBuilder().apply {
            append("<html>")
            append("<span style='display:inline-block;width:8px;height:8px;border-radius:50%;background:$dotColor;margin-right:6px'>&nbsp;</span>")
            append("<b>${server.config.id}</b>")
            append(" <span style='color:$dotColor;font-size:11px'>$stateLabel</span>")
            append("<br><span style='color:$dimHex;font-size:11px'>command: ${server.config.command}</span>")
            append(
                "<br><span style='color:$dimHex;font-size:11px'>tools: ${
                server.registeredToolNames.joinToString(
                    ", "
                ).ifEmpty { "(none)" }
            } (${server.registeredToolNames.size})</span>")

            // 初始化中：显示"最多等待 3 分钟"提示
            if (server.state == McpManager.State.INITIALIZING) {
                append("<br><span style='color:$dimHex;font-size:11px'>正在安装依赖 (npm install)...</span>")
                append("<br><span style='color:$dimHex;font-size:11px'>最多等待 3 分钟</span>")
            }

            // 崩溃/错误状态：显示错误详情
            val showErrorDetail = server.state == McpManager.State.CRASHED
                    || server.state == McpManager.State.ERROR
                    || server.state == McpManager.State.INIT_ERROR
            if (showErrorDetail && server.lastErrorMessage != null) {
                append("<br><span style='color:$redHex;font-size:11px'>错误: ${server.lastErrorMessage}</span>")
            }

            append("</html>")
        }
        val info = JLabel(htmlBuilder.toString())
        card.add(info, BorderLayout.CENTER)

        // 操作按钮
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        actions.add(JButton("▶ 测试连接").apply {
            font = font.deriveFont(11f)
            addActionListener {
                val result = manager.testConnection(server.config.id)
                val message = if (result.success) {
                    "🟢 连接正常，发现 ${result.toolCount ?: 0} 个工具 (${result.latencyMs}ms)"
                } else {
                    "🔴 连接失败: ${result.errorMessage ?: "未知错误"}"
                }
                JOptionPane.showMessageDialog(
                    this@McpPage,
                    message,
                    "MCP 测试连接",
                    if (result.success) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
                )
            }
        })
        actions.add(JButton(server.primaryActionLabel()).apply {
            font = font.deriveFont(11f)
            addActionListener {
                val ok = if (server.state == McpManager.State.RUNNING) {
                    manager.disconnect(server.config.id)
                    true
                } else {
                    manager.disconnect(server.config.id)
                    manager.connect(server.config.id)
                }
                refreshList()
                if (!ok) {
                    JOptionPane.showMessageDialog(
                        this@McpPage,
                        "连接失败",
                        "MCP",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        })
        actions.add(JButton("✏ 编辑").apply {
            font = font.deriveFont(11f)
            addActionListener {
                editingServerId = server.config.id
                nameField.text = server.config.id
                cmdField.text = server.config.command
                addForm.isVisible = true
                nameField.requestFocusInWindow()
            }
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

    private fun McpManager.McpServer.primaryActionLabel(): String =
        when (state) {
            McpManager.State.RUNNING, McpManager.State.INITIALIZING -> "⏹ 停止"
            McpManager.State.ERROR, McpManager.State.CRASHED, McpManager.State.INIT_ERROR -> "🔄 重连"
            else -> "▶ 启动"
        }
}
