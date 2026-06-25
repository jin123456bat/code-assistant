package com.aiassistant.ui.page

import com.aiassistant.mcp.McpManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class McpPage(project: Project) : JPanel(BorderLayout()) {

    private val manager = McpManager(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val addForm = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        // Header
        val header = JPanel(FlowLayout(FlowLayout.LEFT))
        header.add(JLabel("<html><b style='font-size:16px'>🔌 MCP Servers</b></html>"))
        header.add(JButton("➕ 添加").apply {
            addActionListener { addForm.isVisible = !addForm.isVisible }
        })
        add(header, BorderLayout.NORTH)

        // Add form
        addForm.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0xE5E7EB, 0x374151)),
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

        // List
        val topPanel = JPanel(BorderLayout())
        topPanel.add(header, BorderLayout.NORTH)
        topPanel.add(addForm, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)
        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // Footer
        add(
            JLabel("<html><span style='color:#6B7280;font-size:11px'>配置文件: .code-assistant/mcp-config.json · 兼容 .mcp.json · ~/.claude/.mcp.json</span></html>"),
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
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xE5E7EB, 0x374151)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
        }
        val dot = when (server.state) {
            McpManager.State.RUNNING -> "<span style='color:#22C55E'>🟢</span>"
            McpManager.State.INITIALIZING, McpManager.State.CONFIGURED -> "<span style='color:#F59E0B'>🟡</span>"
            else -> "<span style='color:#EF4444'>🔴</span>"
        }
        card.add(
            JLabel("<html>$dot <b>${server.config.id}</b> &nbsp;<span style='color:#6B7280'>command: ${server.config.command}</span></html>"),
            BorderLayout.CENTER
        )

        val delBtn = JButton("🗑").apply {
            isContentAreaFilled = false; border = BorderFactory.createEmptyBorder()
            addActionListener { manager.removeServer(server.config.id); refreshList() }
        }
        card.add(delBtn, BorderLayout.EAST)
        return card
    }

    private fun renderEmpty(): JPanel {
        return JPanel().apply {
            add(JLabel("<html><div style='text-align:center;padding:40px;color:#6B7280'>还没有 MCP Server<br><span style='font-size:11px'>添加 MCP Server 连接外部工具</span></div></html>"))
        }
    }
}
