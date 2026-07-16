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
        // 状态指示灯 + 名称 + 状态
        // 文档 §六 定义: 🟢 RUNNING / 🟡 INITIALIZING / 🔴 CRASHED / ERROR
        val (stateColor, stateLabel) = when (server.state) {
            McpManager.State.RUNNING -> AppColors.success to "RUNNING"
            McpManager.State.INITIALIZING -> AppColors.warning to "INITIALIZING"
            McpManager.State.CONFIGURED -> AppColors.textSecondary to "CONFIGURED"
            McpManager.State.CRASHED -> AppColors.error to "CRASHED"
            McpManager.State.ERROR -> AppColors.error to "ERROR"
            McpManager.State.INIT_ERROR -> AppColors.error to "ERROR"
            else -> AppColors.textSecondary to "${server.state}"
        }

        // 标题和主操作独占一行，避免窄 ToolWindow 中按钮栏挤压详情内容。
        val titleRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        val titleContent = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(JLabel("●").apply { foreground = stateColor }, BorderLayout.WEST)
            add(
                ElidingLabel(value = server.config.id).apply {
                    font = font.deriveFont(java.awt.Font.BOLD)
                },
                BorderLayout.CENTER
            )
            add(JLabel(stateLabel).apply {
                foreground = stateColor
                font = font.deriveFont(11f)
            }, BorderLayout.EAST)
        }
        titleRow.add(titleContent, BorderLayout.CENTER)
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
        val details = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        fun addDetail(label: JLabel) {
            label.alignmentX = LEFT_ALIGNMENT
            label.maximumSize = Dimension(Int.MAX_VALUE, label.preferredSize.height)
            details.add(label)
        }
        addDetail(ElidingLabel("command: ", server.config.command).detailStyle(AppColors.textSecondary))
        addDetail(
            ElidingLabel(
                prefix = "tools: ",
                value = server.registeredToolNames.joinToString(", ").ifEmpty { "(none)" },
                suffix = " (${server.registeredToolNames.size})"
            ).detailStyle(AppColors.textSecondary)
        )
        if (server.schemaValidationFailures.isNotEmpty()) {
            addDetail(
                ElidingLabel("⚠ Schema 校验失败: ", server.schemaValidationFailures.joinToString("; "))
                    .detailStyle(AppColors.warning)
            )
        }
        if (server.state == McpManager.State.INITIALIZING) {
            addDetail(JLabel("正在安装依赖 (npm install)...").detailStyle(AppColors.textSecondary))
            addDetail(JLabel("最多等待 3 分钟").detailStyle(AppColors.textSecondary))
        }
        val showErrorDetail = server.state == McpManager.State.CRASHED
                || server.state == McpManager.State.ERROR
                || server.state == McpManager.State.INIT_ERROR
        if (showErrorDetail && server.lastErrorMessage != null) {
            addDetail(ElidingLabel("错误: ", server.lastErrorMessage.orEmpty()).detailStyle(AppColors.error))
        }
        card.add(details, BorderLayout.CENTER)

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
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(40, 12, 40, 12)
            val content = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JLabel("还没有 MCP Server").apply { alignmentX = CENTER_ALIGNMENT })
                add(Box.createVerticalStrut(4))
                add(JLabel("添加 MCP Server 连接外部工具").apply {
                    alignmentX = CENTER_ALIGNMENT
                    foreground = AppColors.textSecondary
                    font = font.deriveFont(11f)
                })
            }
            add(content, BorderLayout.CENTER)
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

    private fun <T : JLabel> T.detailStyle(color: java.awt.Color): T = apply {
        foreground = color
        font = font.deriveFont(11f)
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

    /** 根据组件的实际像素宽度省略文本，避免按字符数估算导致窄侧栏继续溢出。 */
    private class ElidingLabel(
        private val prefix: String = "",
        private val value: String,
        private val suffix: String = ""
    ) : JLabel() {
        private val fullText = prefix + value + suffix

        init {
            toolTipText = fullText
            updateDisplayedText(DETAIL_FALLBACK_WIDTH)
            minimumSize = Dimension(0, preferredSize.height)
        }

        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            super.setBounds(x, y, width, height)
            updateDisplayedText(width)
        }

        private fun updateDisplayedText(maxWidth: Int) {
            if (maxWidth <= 0) return
            val metrics = getFontMetrics(font)
            if (metrics.stringWidth(fullText) <= maxWidth) {
                text = fullText
                return
            }

            val fixedText = prefix + ELLIPSIS + suffix
            if (metrics.stringWidth(fixedText) > maxWidth) {
                text = elide(fullText, maxWidth, metrics)
                return
            }

            var low = 0
            var high = value.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val candidate = prefix + value.take(middle) + ELLIPSIS + suffix
                if (metrics.stringWidth(candidate) <= maxWidth) low = middle else high = middle - 1
            }
            text = prefix + value.take(low) + ELLIPSIS + suffix
        }

        private fun elide(source: String, maxWidth: Int, metrics: java.awt.FontMetrics): String {
            var low = 0
            var high = source.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                val candidate = source.take(middle) + ELLIPSIS
                if (metrics.stringWidth(candidate) <= maxWidth) low = middle else high = middle - 1
            }
            return source.take(low) + ELLIPSIS
        }
    }

    private companion object {
        const val DETAIL_FALLBACK_WIDTH = 200
        const val ELLIPSIS = "…"
    }
}
