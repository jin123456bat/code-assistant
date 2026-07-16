package com.aiassistant.ui.page

import com.aiassistant.mcp.McpManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.*

class McpPage(project: Project) : JPanel(BorderLayout()), Disposable {

    private val manager = McpManager(project)
    private val listContainer = ViewportWidthPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val addForm = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val nameField = JTextField(15)
    private val cmdField = JTextField(25)
    private var editingServerId: String? = null
    private var disposed = false

    init {
        val header = JPanel(BorderLayout())
        header.add(
            JLabel("<html><b style='font-size:16px'>🔌 MCP Servers</b></html>"),
            BorderLayout.WEST
        )
        header.add(JButton("➕ 添加").apply {
            addActionListener {
                editingServerId = null
                nameField.text = ""
                cmdField.text = ""
                addForm.isVisible = !addForm.isVisible
                revalidate(); repaint()
            }
        }, BorderLayout.EAST)

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
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
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
        manager.loadServers()
        val servers = manager.getAllServers()
        if (servers.isEmpty()) {
            listContainer.add(renderEmpty())
        } else {
            servers.forEach { listContainer.add(renderCard(it)) }
        }
        listContainer.revalidate(); listContainer.repaint()
    }

    private fun renderCard(server: McpManager.McpServer): JPanel {
        val card = JPanel(BorderLayout(0, 6)).apply {
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

        // 标题和主操作独占一行，避免窄 ToolWindow 中按钮栏挤压详情内容。
        val titleRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        titleRow.add(
            JLabel(
                "<html><span style='color:$dotColor'>●</span> " +
                        "<b>${server.config.id.cardText()}</b> " +
                        "<span style='color:$dotColor;font-size:11px'>$stateLabel</span></html>"
            ).apply { toolTipText = server.config.id },
            BorderLayout.CENTER
        )
        titleRow.add(JButton(server.primaryActionLabel()).compactAction("启动、停止或重连此 MCP Server").apply {
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
        }, BorderLayout.EAST)
        card.add(titleRow, BorderLayout.NORTH)

        // 详情单独占据卡片主体，状态变化时增加的错误信息不会改变操作区的水平布局。
        val htmlBuilder = StringBuilder().apply {
            append("<html>")
            append("<span style='color:$dimHex;font-size:11px'>command: ${server.config.command.cardText()}</span>")
            append(
                "<br><span style='color:$dimHex;font-size:11px'>tools: ${
                server.registeredToolNames.joinToString(
                    ", "
                ).ifEmpty { "(none)" }.cardText()
            } (${server.registeredToolNames.size})</span>")

            // Schema 校验失败警告
            if (server.schemaValidationFailures.isNotEmpty()) {
                append("<br><span style='color:$amberHex;font-size:11px'>⚠ Schema 校验失败: ${
                    server.schemaValidationFailures.joinToString("; ").cardText()
                }</span>")
            }

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
                append("<br><span style='color:$redHex;font-size:11px'>错误: ${server.lastErrorMessage.orEmpty().cardText()}</span>")
            }

            append("</html>")
        }
        val info = JLabel(htmlBuilder.toString()).apply {
            toolTipText = buildString {
                append("<html>command: ${server.config.command.escapeHtml()}")
                append("<br>tools: ${server.registeredToolNames.joinToString(", ").ifEmpty { "(none)" }.escapeHtml()}")
                if (server.schemaValidationFailures.isNotEmpty()) {
                    append("<br>Schema 校验失败: ${server.schemaValidationFailures.joinToString("; ").escapeHtml()}")
                }
                server.lastErrorMessage?.let { append("<br>错误: ${it.escapeHtml()}") }
                append("</html>")
            }
        }
        card.add(info, BorderLayout.CENTER)

        // 次要操作放到底部并使用紧凑边距；错误态多一个“查看日志”也不会抢占详情宽度。
        val actionGrid = JPanel(GridLayout(0, 2, 4, 4)).apply { isOpaque = false }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(actionGrid)
        }
        actionGrid.add(JButton("测试连接").compactAction("测试此 MCP Server 的连接").apply {
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
        // CRASHED/ERROR/INIT_ERROR 状态显示"查看日志"按钮
        val showLogBtn = server.state == McpManager.State.CRASHED
                || server.state == McpManager.State.ERROR
                || server.state == McpManager.State.INIT_ERROR
        if (showLogBtn) {
            actionGrid.add(JButton("查看日志").compactAction("查看此 MCP Server 的最近日志").apply {
                addActionListener {
                    val logs = manager.getServerLogs(server.config.id)
                    val textArea = JTextArea().apply {
                        font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 11)
                        isEditable = false
                        lineWrap = false
                        rows = 25
                        columns = 80
                        text = if (logs.isEmpty()) "(无日志)" else logs.joinToString("\n")
                        caretPosition = text.length // 滚动到末尾
                    }
                    val scrollPane = JScrollPane(textArea)
                    scrollPane.preferredSize = java.awt.Dimension(650, 400)
                    JOptionPane.showMessageDialog(
                        this@McpPage,
                        scrollPane,
                        "MCP Server 日志: ${server.config.id}",
                        JOptionPane.PLAIN_MESSAGE
                    )
                }
            })
        }
        actionGrid.add(JButton("编辑").compactAction("编辑此 MCP Server 配置").apply {
            addActionListener {
                editingServerId = server.config.id
                nameField.text = server.config.id
                cmdField.text = server.config.command
                addForm.isVisible = true
                nameField.requestFocusInWindow()
            }
        })
        actionGrid.add(JButton("删除").compactAction("删除此 MCP Server 配置").apply {
            foreground = AppColors.error
            addActionListener { manager.removeServer(server.config.id); refreshList() }
        })
        card.add(actions, BorderLayout.SOUTH)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    private fun renderEmpty(): JPanel {
        val dimHex = AppColors.textSecondary.toHtmlColor()
        return JPanel().apply {
            add(JLabel("<html><div style='text-align:center;padding:40px;color:$dimHex'>还没有 MCP Server<br><span style='font-size:11px'>添加 MCP Server 连接外部工具</span></div></html>"))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun McpManager.McpServer.primaryActionLabel(): String =
        when (state) {
            McpManager.State.RUNNING, McpManager.State.INITIALIZING -> "停止"
            McpManager.State.ERROR, McpManager.State.CRASHED, McpManager.State.INIT_ERROR -> "重连"
            else -> "启动"
        }

    private fun JButton.compactAction(accessibleDescription: String): JButton = apply {
        font = font.deriveFont(11f)
        margin = Insets(2, 6, 2, 6)
        accessibleContext.accessibleDescription = accessibleDescription
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.cardText(): String {
        val displayText = if (length <= CARD_TEXT_LIMIT) this else take(CARD_TEXT_LIMIT - 1) + "…"
        return displayText.escapeHtml()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        manager.dispose()
    }

    /** JScrollPane 禁用横向滚动时，列表必须始终采用 viewport 宽度，否则长命令会把整页撑出可视区。 */
    private class ViewportWidthPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int
        ): Int = 16

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle,
            orientation: Int,
            direction: Int
        ): Int = maxOf(visibleRect.height - 16, 16)

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val CARD_TEXT_LIMIT = 48
    }
}
