package com.aiassistant.ui.page

import com.aiassistant.session.SessionIndex
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*

class SessionsPage(
    project: Project,
    private val onRestore: (String) -> Unit
) : JPanel(BorderLayout()) {

    private val store = SessionStore(project)
    private val listContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val searchField = JTextField().apply {
        putClientProperty("JTextField.placeholderText", "🔍 搜索会话...")
    }
    private val checkboxes = mutableMapOf<String, JCheckBox>()
    private val statsLabel = JLabel()
    private lateinit var clearBtn: JButton
    private lateinit var selectAllBtn: JButton
    private lateinit var deleteSelectedBtn: JButton

    private val pageSize = 20
    private var allSessions: List<SessionIndex> = emptyList()
    private var displayedCount = 0
    private lateinit var loadMoreBtn: JButton

    init {
        val topBar = JPanel(BorderLayout())
        topBar.add(searchField, BorderLayout.CENTER)
        clearBtn = JButton("🗑 清空").apply {
            addActionListener {
                if (JOptionPane.showConfirmDialog(
                        this@SessionsPage,
                        "确认清空所有会话？此操作不可撤销",
                        "清空会话",
                        JOptionPane.YES_NO_OPTION
                    ) == JOptionPane.YES_OPTION
                ) {
                    store.clear(); refreshList()
                }
            }
        }
        topBar.add(clearBtn, BorderLayout.EAST)
        add(topBar, BorderLayout.NORTH)

        // 会话列表 + 加载更多
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        loadMoreBtn = JButton("加载更多...").apply {
            isVisible = false
            addActionListener { loadMore() }
        }
        centerPanel.add(loadMoreBtn, BorderLayout.SOUTH)
        add(centerPanel, BorderLayout.CENTER)

        // Bottom: 统计 + 批量操作（无会话时隐藏按钮）
        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT))
        bottomBar.add(statsLabel)
        selectAllBtn = JButton("全选").apply {
            addActionListener {
                val allSelected = checkboxes.values.all { it.isSelected }
                checkboxes.values.forEach { it.isSelected = !allSelected }
                text = if (allSelected) "全选" else "取消全选"
            }
        }
        deleteSelectedBtn = JButton("删除选中").apply {
            addActionListener {
                val toDelete = checkboxes.filter { it.value.isSelected }.keys
                toDelete.forEach { store.delete(it) }
                refreshList()
            }
        }
        bottomBar.add(selectAllBtn)
        bottomBar.add(deleteSelectedBtn)
        add(bottomBar, BorderLayout.SOUTH)

        // 搜索 debounce
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

    private fun loadMore() {
        displayedCount = (displayedCount + pageSize).coerceAtMost(allSessions.size)
        renderLoaded()
    }

    fun refreshList() {
        displayedCount = 0
        val query = searchField.text.lowercase()
        allSessions = store.listAll()
            .filter { query.isEmpty() || it.title.lowercase().contains(query) }
            .sortedByDescending { it.updatedAt }
        displayedCount = pageSize.coerceAtMost(allSessions.size)
        renderLoaded()
    }

    private fun renderLoaded() {
        listContainer.removeAll(); checkboxes.clear()
        val visible = allSessions.take(displayedCount)

        if (allSessions.isEmpty()) {
            val dimHex = AppColors.textSecondary.toHtmlColor()
            listContainer.add(Box.createVerticalGlue())
            val emptyLabel = JLabel(
                "<html><div style='text-align:center;color:$dimHex'>📁<br><br>还没有会话记录<br><span style='font-size:11px'>开始一段对话，会话将自动保存</span></div></html>",
                SwingConstants.CENTER
            )
            emptyLabel.alignmentX = java.awt.Component.CENTER_ALIGNMENT
            listContainer.add(emptyLabel)
            listContainer.add(Box.createVerticalGlue())
            statsLabel.text = ""
        } else {
            visible.forEach { listContainer.add(renderCard(it)) }
            val totalTokens = allSessions.sumOf { it.totalTokens }
            statsLabel.text = "共 ${allSessions.size} 个会话，总消耗 ${totalTokens / 1000}K tokens"
        }

        loadMoreBtn.isVisible = displayedCount < allSessions.size
        if (loadMoreBtn.isVisible) {
            loadMoreBtn.text = "加载更多... (已显示 $displayedCount / ${allSessions.size})"
        }

        // 无会话时隐藏清空和批量操作按钮
        val hasSessions = allSessions.isNotEmpty()
        clearBtn.isVisible = hasSessions
        selectAllBtn.isVisible = hasSessions
        deleteSelectedBtn.isVisible = hasSessions

        listContainer.revalidate(); listContainer.repaint()
    }

    private fun renderCard(s: SessionIndex): JPanel {
        if (s.corrupted) return renderCorruptedCard(s)

        val isChild = s.parentId != null
        val indentPixels = if (isChild) 24 else 0

        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12 + indentPixels, 8, 12)
            )
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 72)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.x > 30) onRestore(s.id)
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
        val titlePrefix = if (isChild) "└─ " else ""

        card.add(
            JLabel("<html><b>$titlePrefix${s.title}</b>$planStatus<br><span style='color:$dimHex;font-size:11px'>$time · ${s.totalTokens / 1000}K tokens · ${s.toolCallCount} 次工具</span></html>"),
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

    private fun renderCorruptedCard(s: SessionIndex): JPanel {
        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 72)
            isEnabled = false
        }
        val dimHex = AppColors.textSecondary.toHtmlColor()
        card.add(
            JLabel("<html><span style='color:$dimHex'>⚠️ 会话文件损坏 ($s.id.json)</span></html>"),
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
}
