package com.aiassistant.ui.page

import com.aiassistant.session.SessionIndex
import com.aiassistant.session.SessionStore
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

class SessionsPage(
    project: Project,
    private val onRestore: (String) -> Unit,
    private val onNewSession: () -> Unit
) : JPanel(BorderLayout()) {

    private val store = SessionStore(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val searchField = JTextField().apply {
        putClientProperty("JTextField.placeholderText", "搜索会话...")
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
        bottomBar.add(JButton("导出 JSON").apply { /* ponytail: export later */ })
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
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor(0xE5E7EB, 0x374151)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 60)
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
        card.add(
            JLabel("<html><b>${s.title}</b><br><span style='color:#6B7280;font-size:11px'>$time · ${s.totalTokens / 1000}K tokens · ${s.toolCallCount} 次工具</span></html>"),
            BorderLayout.CENTER
        )

        val delBtn = JButton("✕").apply {
            isContentAreaFilled = false; border = BorderFactory.createEmptyBorder()
            foreground = JBColor(0xEF4444, 0xF87171)
            addActionListener { store.delete(s.id); refreshList() }
        }
        card.add(delBtn, BorderLayout.EAST)
        return card
    }

    private fun renderEmpty(): JPanel {
        val p = JPanel(BorderLayout())
        p.add(
            JLabel(
                "<html><div style='text-align:center;padding:40px;color:#6B7280'>还没有会话记录<br><span style='font-size:11px'>开始一段对话，会话将自动保存</span></div></html>",
                SwingConstants.CENTER
            )
        )
        return p
    }
}
