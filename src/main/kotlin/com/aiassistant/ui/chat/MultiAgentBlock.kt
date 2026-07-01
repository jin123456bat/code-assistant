package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * 多 Agent 调度卡片（内联折叠块）。
 *
 * 对齐文档 docs/ui/chat.md §六：
 * - 头部可点击折叠/展开整个卡片（箭头 ▶/▾ 切换），默认折叠
 * - 子 Agent 行：图标 + 名称/任务 + 状态（✅完成 / 🔄执行中 / ⏸排队）
 * - 点击子 Agent 行 → 展开/折叠该子 Agent 的详细执行过程
 * - 展开后：流式文本实时追加 + 子 ToolCallCard 嵌套展示 + 错误红色标注 + 底部耗时/sessionId
 */
class MultiAgentBlock(
    private val onSessionClick: ((sessionId: String) -> Unit)? = null
) : JPanel(BorderLayout()) {

    // ── 子 Agent 状态枚举 ──
    enum class SubAgentState(val label: String, val icon: String) {
        PENDING("排队中", "⏸"),
        RUNNING("执行中", "🔄"),
        DONE("已完成", "✅"),
        ERROR("错误", "❌"),
        TIMEOUT("超时", "⏱"),
        CANCELLED("已取消", "🚫")
    }

    /** 单个子 Agent 的数据模型 */
    data class SubAgentInfo(
        val id: String,
        val name: String,
        val task: String,
        var state: SubAgentState = SubAgentState.PENDING,
        val sessionId: String? = null
    )

    /** 子 Agent 行 UI 组件 */
    class SubAgentRow(
        val info: SubAgentInfo,
        val onToggle: (SubAgentRow) -> Unit,
        private val onSessionClick: ((String) -> Unit)? = null
    ) : JPanel(BorderLayout()) {
        val toggleButton =
            JButton().apply { accessibleContext.accessibleDescription = "展开或折叠子Agent详情" }
        private val statusLabel = JLabel()
        private val nameLabel = JLabel()

        /** 详情面板（可折叠） */
        val detailPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 16, 4, 8)
            isVisible = false
        }
        private val streamTextArea = JTextArea().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
            isEditable = false
            background = AppColors.codeBg
            foreground = AppColors.textSecondary
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppColors.border),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            lineWrap = true
            isVisible = false
        }
        private val footerLabel = JLabel().apply {
            font = font.deriveFont(10f)
            foreground = AppColors.textTertiary
        }
        var isExpanded = false
            private set

        /** 子 ToolCallCard 容器 */
        private val toolCallContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = false
        }
        private val toolCards = mutableMapOf<String, ToolCallCard>()

        init {
            // 对齐 ui-prototype .ma-sub: bg=#FFFFFF, border=1px solid #F3F4F6, padding=6px 10px
            isOpaque = true
            background = AppColors.cardBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AppColors.hoverBg, 1),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
            )

            val leftPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
            }
            toggleButton.apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 0, 0, 4)
                font = font.deriveFont(10f)
                addActionListener { onToggle(this@SubAgentRow) }
            }
            updateToggleButton()
            nameLabel.apply {
                font = font.deriveFont(12f)
                text = "<html>${info.name}: ${info.task.take(60)}</html>"
            }
            leftPanel.add(toggleButton, BorderLayout.WEST)
            leftPanel.add(nameLabel, BorderLayout.CENTER)

            statusLabel.apply {
                font = font.deriveFont(11f)
                text = "${info.state.icon} ${info.state.label}"
            }
            add(leftPanel, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)

            detailPanel.add(streamTextArea)
            detailPanel.add(toolCallContainer)
            detailPanel.add(footerLabel)
        }

        private fun updateToggleButton() {
            toggleButton.text = if (isExpanded) "▼" else "▶"
        }

        fun toggle() {
            // 排队的子 Agent 不可展开（文档要求：docs/ui/chat.md §六）
            if (info.state == SubAgentState.PENDING) return
            isExpanded = !isExpanded
            updateToggleButton()
            detailPanel.isVisible = isExpanded
            streamTextArea.isVisible = isExpanded
            toolCallContainer.isVisible = isExpanded
            revalidate()
            repaint()
        }

        fun setState(state: SubAgentState) {
            info.state = state
            statusLabel.text = "${state.icon} ${state.label}"
            when (state) {
                SubAgentState.ERROR -> statusLabel.foreground = AppColors.error
                SubAgentState.TIMEOUT -> statusLabel.foreground = AppColors.warning
                SubAgentState.RUNNING -> statusLabel.foreground = AppColors.primary
                SubAgentState.DONE -> statusLabel.foreground = AppColors.success
                else -> statusLabel.foreground = AppColors.textSecondary
            }
        }

        fun appendStreamText(text: String) {
            streamTextArea.append(text)
        }

        fun setFooter(durationMs: Long, sessionId: String?) {
            val durText = if (durationMs > 0) "${durationMs}ms" else ""
            val sessionText = if (sessionId != null) {
                "详情: sub-session #${sessionId.take(8)}"
            } else ""
            footerLabel.text = listOfNotNull(durText, sessionText).joinToString(" | ")
            // sessionId 可点击跳转
            if (sessionId != null && onSessionClick != null) {
                footerLabel.text =
                    "<html><span style='color:${AppColors.textTertiary.toHtmlColor()}'>${
                        listOfNotNull(
                            durText,
                            "详情: sub-session #${sessionId.take(8)}"
                        ).joinToString(" | ")
                    }</span></html>"
            }
        }

        fun markError(errorMessage: String) {
            streamTextArea.foreground = AppColors.error
            streamTextArea.append("\n❌ $errorMessage\n")
        }

        fun addToolCard(toolUseId: String, toolName: String, params: String): ToolCallCard {
            val card = ToolCallCard(toolName, params, ToolCallCard.ToolCallState.PENDING)
            toolCards[toolUseId] = card
            toolCallContainer.add(card)
            toolCallContainer.add(Box.createVerticalStrut(4))
            toolCallContainer.revalidate()
            toolCallContainer.repaint()
            return card
        }

        fun updateToolCardState(
            toolUseId: String,
            state: ToolCallCard.ToolCallState,
            result: String?,
            durationMs: Long?
        ) {
            toolCards[toolUseId]?.setState(state, result, durationMs)
            toolCallContainer.revalidate()
            toolCallContainer.repaint()
        }
    }

    /** 文件锁数据模型：记录哪个文件被哪个子 Agent 锁定 */
    data class FileLock(
        val fileName: String,
        val agentId: String
    )

    // ── MultiAgentBlock 主组件 ──
    /** 是否整体折叠（默认折叠，对齐 docs/ui/chat.md §六） */
    private var isCollapsed = true
    private val arrowLabel = JLabel("▶").apply {
        font = font.deriveFont(12f)
    }
    private val headerLabel = JLabel()
    private val concurrencyLabel = JLabel()
    private val footerPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
    }
    private val fileLockLabel = JLabel().apply {
        font = font.deriveFont(10f)
        foreground = AppColors.textTertiary
        text = ""
    }
    private val subAgentContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val subAgents = mutableMapOf<String, SubAgentRow>()
    private val fileLocks = mutableListOf<FileLock>()
    private var totalSlots = 0
    private var runningCount = 0

    /** 当前子 Agent 数量（供 ChatViewModel 判断是否为首次创建） */
    val subAgentCount: Int get() = subAgents.size

    init {
        // 对齐 ui-prototype.html .multi-agent-block: padding=12px
        border = BorderFactory.createCompoundBorder(
            RoundedBorder(8, AppColors.multiAgentBorder),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )
        isOpaque = true
        background = AppColors.multiAgentBg

        // 头部：箭头 + 标题 + 并发状态，可点击折叠/展开整个卡片
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.headerBg
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    toggleCollapse()
                }
            })
        }
        val headerLeft = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        arrowLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
        headerLeft.add(arrowLabel, BorderLayout.WEST)
        headerLabel.apply {
            font = font.deriveFont(Font.BOLD, 12f)
            text = "🤖 多 Agent 调度中"
        }
        headerLeft.add(headerLabel, BorderLayout.CENTER)
        header.add(headerLeft, BorderLayout.WEST)
        concurrencyLabel.apply {
            font = font.deriveFont(11f)
            foreground = AppColors.textSecondary
            text = ""
        }
        header.add(concurrencyLabel, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)

        // 子 Agent 列表
        add(subAgentContainer, BorderLayout.CENTER)

        // 底部状态行：文件锁信息
        footerPanel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        footerPanel.add(fileLockLabel, BorderLayout.WEST)
        add(footerPanel, BorderLayout.SOUTH)

        // 应用默认折叠状态
        applyCollapseState()
    }

    /** 切换整个卡片的折叠/展开状态 */
    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        applyCollapseState()
    }

    /** 根据当前折叠状态显示/隐藏子元素 */
    private fun applyCollapseState() {
        arrowLabel.text = if (isCollapsed) "▶" else "▾"
        subAgentContainer.isVisible = !isCollapsed
        footerPanel.isVisible = !isCollapsed
        // 折叠时也隐藏各子 Agent 的 detailPanel
        if (isCollapsed) {
            subAgents.values.forEach { row ->
                row.detailPanel.isVisible = false
            }
        }
        revalidate()
        repaint()
    }

    /**
     * 设置并发状态显示。
     * @param running 当前运行中的子 Agent 数
     * @param total 总并发槽位数
     */
    fun setConcurrency(running: Int, total: Int) {
        runningCount = running
        totalSlots = total
        updateConcurrencyLabel()
        updateFooterLabel()
    }

    /**
     * 设置文件锁信息，格式为 "文件锁: UserService.kt (B)"。
     * @param locks 文件锁列表，每个元素包含文件名和持有该锁的子 Agent ID
     */
    fun setFileLocks(locks: List<FileLock>) {
        fileLocks.clear()
        fileLocks.addAll(locks)
        updateFooterLabel()
    }

    /** 更新头部并发状态标签文本 */
    private fun updateConcurrencyLabel() {
        if (totalSlots > 0) {
            concurrencyLabel.text = "$runningCount/$totalSlots 运行中"
        } else {
            concurrencyLabel.text = if (runningCount > 0) "$runningCount 运行中" else ""
        }
    }

    /** 更新底部状态行（并发上限 + 文件锁），格式："并发上限: 3 | 文件锁: UserService.kt (B)" */
    private fun updateFooterLabel() {
        val parts = mutableListOf<String>()
        // 并发上限部分
        if (totalSlots > 0) {
            parts.add("并发上限: $totalSlots")
        }
        // 文件锁部分
        if (fileLocks.isNotEmpty()) {
            val lockTexts = fileLocks.joinToString(", ") { lock ->
                "${lock.fileName} (${lock.agentId})"
            }
            parts.add("文件锁: $lockTexts")
        }
        fileLockLabel.text = parts.joinToString(" | ")
    }

    /**
     * 添加一个子 Agent 行。
     * @return 创建的 SubAgentRow，可用于后续更新状态和流式文本
     */
    fun addSubAgent(info: SubAgentInfo): SubAgentRow {
        val row = SubAgentRow(info, { r -> r.toggle() }, onSessionClick)
        subAgents[info.id] = row
        subAgentContainer.add(row)
        subAgentContainer.add(row.detailPanel)
        subAgentContainer.revalidate()
        subAgentContainer.repaint()
        return row
    }

    /**
     * 获取或创建子 Agent 行。如果已存在则返回现有行，否则创建新的。
     */
    fun getOrCreateSubAgent(info: SubAgentInfo): SubAgentRow {
        return subAgents[info.id] ?: addSubAgent(info)
    }

    /**
     * 更新子 Agent 状态。
     */
    fun setSubAgentState(agentId: String, state: SubAgentState) {
        val row = subAgents[agentId] ?: return
        row.setState(state)
        // 更新运行计数
        recalculateConcurrency()
        subAgentContainer.revalidate()
        subAgentContainer.repaint()
    }

    /**
     * 向子 Agent 追加流式文本。
     */
    fun appendSubAgentStream(agentId: String, text: String) {
        subAgents[agentId]?.appendStreamText(text)
    }

    /**
     * 设置子 Agent 底部信息（耗时 + sessionId）。
     */
    fun setSubAgentFooter(agentId: String, durationMs: Long, sessionId: String?) {
        subAgents[agentId]?.setFooter(durationMs, sessionId)
    }

    /**
     * 标记子 Agent 错误。
     */
    fun markSubAgentError(agentId: String, errorMessage: String) {
        subAgents[agentId]?.markError(errorMessage)
    }

    /**
     * 为子 Agent 添加 ToolCallCard。
     */
    fun addSubAgentToolCard(
        agentId: String,
        toolUseId: String,
        toolName: String,
        params: String
    ): ToolCallCard? {
        return subAgents[agentId]?.addToolCard(toolUseId, toolName, params)
    }

    /**
     * 更新子 Agent 的 ToolCallCard 状态。
     */
    fun updateSubAgentToolCard(
        agentId: String,
        toolUseId: String,
        state: ToolCallCard.ToolCallState,
        result: String?,
        durationMs: Long?
    ) {
        subAgents[agentId]?.updateToolCardState(toolUseId, state, result, durationMs)
    }

    /**
     * 展开指定子 Agent 的详情面板。
     */
    fun expandSubAgent(agentId: String) {
        val row = subAgents[agentId] ?: return
        if (!row.isExpanded) {
            // 排队的子 Agent 不可展开（文档要求：docs/ui/chat.md §六）
            if (row.info.state != SubAgentState.PENDING) {
                row.toggle()
            }
        }
    }

    /**
     * 折叠指定子 Agent 的详情面板。
     */
    fun collapseSubAgent(agentId: String) {
        val row = subAgents[agentId] ?: return
        if (row.isExpanded) {
            row.toggle()
        }
    }

    /** 根据当前所有子 Agent 状态重新计算并发统计 */
    private fun recalculateConcurrency() {
        runningCount = subAgents.values.count { it.info.state == SubAgentState.RUNNING }
        updateConcurrencyLabel()
        updateFooterLabel()
    }

    /**
     * 展开整个卡片（用于外部主动展开，如首次有子 Agent 开始执行时）。
     */
    fun expand() {
        if (isCollapsed) {
            toggleCollapse()
        }
    }

    /**
     * 折叠整个卡片（用于外部主动折叠）。
     */
    fun collapse() {
        if (!isCollapsed) {
            toggleCollapse()
        }
    }

    /** 获取当前活跃（RUNNING）子 Agent 数量 */
    fun getActiveSubAgentCount(): Int =
        subAgents.values.count { it.info.state == SubAgentState.RUNNING }

    /** 清除所有子 Agent 行并重置状态 */
    fun clear() {
        subAgents.clear()
        subAgentContainer.removeAll()
        fileLocks.clear()
        runningCount = 0
        totalSlots = 0
        isCollapsed = true
        applyCollapseState()
        recalculateConcurrency()
        revalidate()
        repaint()
    }
}
