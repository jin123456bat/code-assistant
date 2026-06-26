package com.aiassistant.ui.page

import com.aiassistant.session.SessionIndex
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class SessionsPage(
    project: Project,
    private val onRestore: (String) -> Unit,
    private val onNewSession: () -> Unit
) : JPanel(BorderLayout()) {

    private val store = SessionStore(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val searchField = JTextField().apply {
        putClientProperty("JTextField.placeholderText", "🔍 搜索会话...")
    }
    private val checkboxes = mutableMapOf<String, JCheckBox>()

    init {
        // Search bar
        val topBar = JPanel(BorderLayout())
        topBar.add(searchField, BorderLayout.CENTER)
        add(topBar, BorderLayout.NORTH)

        // Session list
        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // Bottom bar with batch operations
        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT))
        bottomBar.add(JButton("新建会话").apply { addActionListener { onNewSession() } })
        val selectAllBtn = JButton("全选").apply {
            addActionListener {
                val allSelected = checkboxes.values.all { it.isSelected }
                checkboxes.values.forEach { it.isSelected = !allSelected }
                text = if (allSelected) "全选" else "取消全选"
            }
        }
        bottomBar.add(selectAllBtn)
        bottomBar.add(JButton("删除选中").apply {
            addActionListener {
                val toDelete = checkboxes.filter { it.value.isSelected }.keys
                toDelete.forEach { store.delete(it) }
                refreshList()
            }
        })
        bottomBar.add(JButton("导出 JSON").apply {
            addActionListener { exportSessions() }
        })
        add(bottomBar, BorderLayout.SOUTH)

        // debounce search
        val timer = Timer(300) { refreshList() }
        timer.isRepeats = false
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                timer.restart()
            }

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                timer.restart()
            }

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                timer.restart()
            }
        })

        refreshList()
    }

    fun refreshList() {
        listContainer.removeAll(); checkboxes.clear()
        val query = searchField.text.lowercase()
        val sessions = store.listAll()
            .filter { query.isEmpty() || it.title.lowercase().contains(query) }
            .sortedByDescending { it.updatedAt }

        if (sessions.isEmpty()) {
            listContainer.add(renderEmpty())
        } else {
            sessions.forEach { listContainer.add(renderCard(it)) }
        }

        listContainer.revalidate()
        listContainer.repaint()
    }

    private fun renderCard(s: SessionIndex): JPanel {
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 72)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.x > 30) onRestore(s.id) // don't restore if clicking checkbox
                }
            })
        }

        val cb = JCheckBox().apply { checkboxes[s.id] = this }
        card.add(cb, BorderLayout.WEST)

        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val time = formatter.format(s.updatedAt)
        val dimHex = AppColors.textSecondary.toHtmlColor()
        val planStatus =
            if (s.hasActivePlan) "<br><span style='color:$dimHex;font-size:11px'>⏸ 计划暂停中</span>" else ""
        card.add(
            JLabel("<html><b>${s.title}</b>$planStatus<br><span style='color:$dimHex;font-size:11px'>$time · ${s.totalTokens / 1000}K tokens · ${s.toolCallCount} 次工具</span></html>"),
            BorderLayout.CENTER
        )

        val delBtn = JButton("✕").apply {
            isContentAreaFilled = false; border = BorderFactory.createEmptyBorder()
            foreground = AppColors.error
            addActionListener { store.delete(s.id); refreshList() }
        }
        card.add(delBtn, BorderLayout.EAST)
        return card
    }

    private fun renderEmpty(): JPanel {
        val p = JPanel(BorderLayout())
        val dimHex = AppColors.textSecondary.toHtmlColor()
        p.add(
            JLabel(
                "<html><div style='text-align:center;padding:40px;color:$dimHex'>还没有会话记录<br><span style='font-size:11px'>开始一段对话，会话将自动保存</span></div></html>",
                SwingConstants.CENTER
            )
        )
        return p
    }

    private fun exportSessions() {
        val ids = checkboxes.filterValues { it.isSelected }.keys.toList()
        if (ids.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "请选择要导出的会话",
                "导出 JSON",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val chooser = JFileChooser().apply {
            dialogTitle = "导出会话 JSON"
            fileFilter = FileNameExtensionFilter("JSON 文件", "json")
            selectedFile = File("code-assistant-sessions-$timestamp.json")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        val target = ensureJsonExtension(chooser.selectedFile)
        if (target.exists()) {
            val answer = JOptionPane.showConfirmDialog(
                this,
                "文件已存在，是否覆盖？",
                "导出 JSON",
                JOptionPane.YES_NO_OPTION
            )
            if (answer != JOptionPane.YES_OPTION) return
        }

        runCatching {
            target.writeText(store.exportJson(ids))
        }.onSuccess {
            JOptionPane.showMessageDialog(
                this,
                "已导出 ${ids.size} 个会话",
                "导出 JSON",
                JOptionPane.INFORMATION_MESSAGE
            )
        }.onFailure { error ->
            JOptionPane.showMessageDialog(
                this,
                "导出失败: ${error.message}",
                "导出 JSON",
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    private fun ensureJsonExtension(file: File): File =
        if (file.name.endsWith(".json", ignoreCase = true)) file else File(
            file.parentFile,
            "${file.name}.json"
        )
}
