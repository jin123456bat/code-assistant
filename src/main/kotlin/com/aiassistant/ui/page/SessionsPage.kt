package com.aiassistant.ui.page

import com.aiassistant.session.SessionIndex
import com.aiassistant.session.SessionStore
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.toHtmlColor
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
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

    /** 分页状态：每页 20 条，对齐 TokenUsagePage 分页规则 */
    private val pageSize = 20
    private var currentPage = 0
    private var totalSessions = 0
    private var allSessions: List<SessionIndex> = emptyList()
    private lateinit var pageLabel: JLabel
    private lateinit var prevBtn: JButton
    private lateinit var nextBtn: JButton

    init {
        // Search bar + clear button (对齐 docs/ui/pages.md §四)
        val topBar = JPanel(BorderLayout())
        topBar.add(searchField, BorderLayout.CENTER)
        topBar.add(JButton("🗑 清空").apply {
            addActionListener {
                val answer = JOptionPane.showConfirmDialog(
                    this@SessionsPage,
                    "确认清空所有会话？此操作不可撤销",
                    "清空会话",
                    JOptionPane.YES_NO_OPTION
                )
                if (answer == JOptionPane.YES_OPTION) {
                    store.clear()
                    refreshList()
                }
            }
        }, BorderLayout.EAST)
        add(topBar, BorderLayout.NORTH)

        // Session list
        add(JScrollPane(listContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // 分页控件
        val paginationBar = JPanel(FlowLayout(FlowLayout.CENTER))
        pageLabel = JLabel()
        prevBtn = JButton("上一页").apply {
            addActionListener {
                if (currentPage > 0) {
                    currentPage--
                    renderCurrentPage()
                }
            }
        }
        nextBtn = JButton("下一页").apply {
            addActionListener {
                if ((currentPage + 1) * pageSize < totalSessions) {
                    currentPage++
                    renderCurrentPage()
                }
            }
        }
        paginationBar.add(prevBtn)
        paginationBar.add(pageLabel)
        paginationBar.add(nextBtn)

        // Bottom bar with batch operations (对齐 docs/ui/pages.md §四 底部栏)
        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT))
        bottomBar.add(statsLabel)
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

        // 底部区域：分页 + 统计/操作
        val southPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(paginationBar)
            add(bottomBar)
        }
        add(southPanel, BorderLayout.SOUTH)

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
        currentPage = 0
        val query = searchField.text.lowercase()
        allSessions = store.listAll()
            .filter { query.isEmpty() || it.title.lowercase().contains(query) }
            .sortedByDescending { it.updatedAt }
        totalSessions = allSessions.size
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        listContainer.removeAll(); checkboxes.clear()
        val fromIndex = currentPage * pageSize
        val pageSessions = allSessions.subList(
            fromIndex,
            (fromIndex + pageSize).coerceAtMost(totalSessions)
        )

        if (allSessions.isEmpty()) {
            listContainer.add(renderEmpty())
            statsLabel.text = ""
            pageLabel.text = ""
            prevBtn.isEnabled = false
            nextBtn.isEnabled = false
        } else {
            pageSessions.forEach { listContainer.add(renderCard(it)) }
            val totalTokens = allSessions.sumOf { it.totalTokens }
            statsLabel.text = "共 ${totalSessions} 个会话，总消耗 ${totalTokens / 1000}K tokens"
            val totalPages = (totalSessions + pageSize - 1) / pageSize
            pageLabel.text = "第 ${currentPage + 1} / $totalPages 页"
            prevBtn.isEnabled = currentPage > 0
            nextBtn.isEnabled = (currentPage + 1) * pageSize < totalSessions
        }

        listContainer.revalidate()
        listContainer.repaint()
    }

    private fun renderCard(s: SessionIndex): JPanel {
        // 损坏文件：灰显、不可点击（对齐 docs/agent/session.md §一 "损坏文件用户感知"）
        if (s.corrupted) return renderCorruptedCard(s)

        // 子会话（parentId 非空）在列表中缩进 + └─ 前缀展示（对齐 docs/ui/pages.md §四）
        val isChild = s.parentId != null
        val indentPixels = if (isChild) 24 else 0

        val card = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(8, 12 + indentPixels, 8, 12)
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

        // 子会话标题前加 └─ 前缀
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

    /**
     * 渲染损坏的会话卡片：灰显、不可点击、仅显示 ⚠️ + 文件名。
     * 对齐 docs/agent/session.md §一 "损坏文件用户感知"。
     */
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

}
