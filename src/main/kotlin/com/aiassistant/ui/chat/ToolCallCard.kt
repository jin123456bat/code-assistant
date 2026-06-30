package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

// 工具调用卡片 — 8 个状态 + 折叠，支持亮/暗主题

class ToolCallCard(
    val toolName: String,
    val params: String,
    initialState: ToolCallState = ToolCallState.PENDING,
    approvalActions: ApprovalActions? = null
) : JPanel(BorderLayout()) {

    data class ApprovalActions(
        val dangerous: Boolean,
        val onAllowOnce: () -> Unit,
        val onAllowSession: () -> Unit,
        val onReject: () -> Unit
    )

    enum class ToolCallState(val label: String, val color: Color, val icon: Icon? = null) {
        PENDING("等待执行", AppColors.textSecondary, AllIcons.Process.Step_1),
        AWAITING_APPROVAL("等待授权", AppColors.warning, AllIcons.General.Warning),
        EXECUTING("执行中...", AppColors.primary, AllIcons.Process.Step_2),
        DONE("完成", AppColors.success, AllIcons.RunConfigurations.TestPassed),
        ERROR("错误", AppColors.error, AllIcons.RunConfigurations.TestFailed),
        TIMEOUT("超时", AppColors.warning, AllIcons.General.Warning),
        REJECTED("已拒绝", AppColors.textSecondary, AllIcons.Actions.Suspend),
        CANCELLED("已取消", AppColors.textSecondary, AllIcons.Actions.Suspend),
    }

    private var state = initialState

    // 折叠状态：默认折叠，AWAITING_APPROVAL 和 EXECUTING 始终展开不可折叠
    private var isCollapsed =
        initialState != ToolCallState.AWAITING_APPROVAL && initialState != ToolCallState.EXECUTING
    private val arrowLabel = JLabel(if (isCollapsed) "▶" else "▾").apply {
        font = font.deriveFont(12f)
    }
    private val headerLabel = JLabel()
    private val headerPanel = JPanel(BorderLayout())
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
    }
    private val bodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
    private val approvalPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        isOpaque = false
        isVisible = false
    }
    private var approvalActions: ApprovalActions? = approvalActions

    // 结果区域容器（JScrollPane），限制 max-height=240px，超出滚动
    private val resultScrollPane = JScrollPane().apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        maximumSize = Dimension(Int.MAX_VALUE, 240)
        border = BorderFactory.createEmptyBorder()
        isOpaque = true
        viewport.isOpaque = true
    }

    // 结果展示区域，可能为 JTextArea（纯文本结果）或 JLabel（HTML Diff 渲染）
    private var resultComponent: JComponent = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        isEditable = false; background = AppColors.codeBg
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
    }
    private val footerLabel = JLabel()

    // 当前状态是否允许折叠
    private val canCollapse: Boolean
        get() = state != ToolCallState.AWAITING_APPROVAL && state != ToolCallState.EXECUTING

    init {
        border = BorderFactory.createCompoundBorder(
            RoundedBorder(8, AppColors.border),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )
        isOpaque = true
        background = AppColors.cardBg

        // 头部：箭头 + 工具名 + 状态标签
        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(arrowLabel, BorderLayout.WEST)
            arrowLabel.border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
            add(JLabel("🔧 $toolName"), BorderLayout.CENTER)
        }
        headerPanel.apply {
            isOpaque = true
            background = AppColors.headerBg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(leftPanel, BorderLayout.WEST)
            add(headerLabel, BorderLayout.EAST)
            // 点击头部切换折叠/展开（仅在可折叠状态下）
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (canCollapse) {
                        toggleCollapse()
                    }
                }

                override fun mouseEntered(e: MouseEvent) {
                    if (canCollapse) {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    cursor = Cursor.getDefaultCursor()
                }
            })
        }
        add(headerPanel, BorderLayout.NORTH)

        val fgHex = AppColors.textSecondary.toHtmlColor()
        val bgHex = AppColors.toolPlaceholderBg.toHtmlColor()
        val paramsLabel =
            JLabel("<html><span style='font-family:monospace;font-size:12px;color:$fgHex;background:$bgHex;padding:4px 8px'>$params</span></html>")
        bodyPanel.add(paramsLabel)
        bodyPanel.add(approvalPanel)
        bodyPanel.add(progressBar)
        resultScrollPane.setViewportView(resultComponent)
        bodyPanel.add(resultScrollPane)
        add(bodyPanel, BorderLayout.CENTER)

        footerLabel.font = footerLabel.font.deriveFont(11f)
        footerLabel.foreground = AppColors.textSecondary
        footerLabel.border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        add(footerLabel, BorderLayout.SOUTH)

        setState(initialState)
        applyCollapseState()
    }

    fun setApprovalActions(actions: ApprovalActions?) {
        approvalActions = actions
        rebuildApprovalPanel()
    }

    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        applyCollapseState()
    }

    private fun applyCollapseState() {
        arrowLabel.text = if (isCollapsed) "▶" else "▾"
        bodyPanel.isVisible = !isCollapsed
        footerLabel.isVisible = !isCollapsed
        // 折叠后需重新计算布局
        revalidate()
        repaint()
    }

    fun setState(newState: ToolCallState, result: String? = null, durationMs: Long? = null) {
        state = newState
        headerLabel.foreground = newState.color
        headerLabel.icon = newState.icon
        headerLabel.text = newState.label
        if (newState == ToolCallState.EXECUTING) {
            progressBar.isVisible = true
        } else {
            progressBar.isVisible = false
        }
        rebuildApprovalPanel()
        if (result != null) {
            setResultContent(result.take(2000))
            resultComponent.isVisible = true
        } else {
            resultComponent.isVisible = false
        }
        footerLabel.text = if (durationMs != null) "${durationMs}ms" else ""

        // AWAITING_APPROVAL 和 EXECUTING 始终展开不可折叠
        if (!canCollapse) {
            isCollapsed = false
        }
        applyCollapseState()
    }

    fun setResult(result: String, durationMs: Long) {
        setResultContent(result.take(2000))
        resultComponent.isVisible = true
        footerLabel.text = "${durationMs}ms"
        // 结果到达后默认折叠（除非当前状态不可折叠）
        if (canCollapse) {
            isCollapsed = true
        }
        applyCollapseState()
    }

    /**
     * 统一的结果内容写入方法，兼容 JTextArea（纯文本结果）和 JLabel（HTML Diff 渲染）。
     * renderDiff() 会将 resultComponent 从 JTextArea 替换为 JLabel，
     * 后续 setState/setResult 调用需通过此方法正确写入对应组件类型。
     */
    private fun setResultContent(content: String) {
        when (resultComponent) {
            is JTextArea -> (resultComponent as JTextArea).text = content
            is JLabel -> (resultComponent as JLabel).text = content
        }
    }

    fun setRejected() = setState(ToolCallState.REJECTED)
    fun setCancelled() = setState(ToolCallState.CANCELLED)

    private fun rebuildApprovalPanel() {
        approvalPanel.removeAll()
        val actions = approvalActions
        approvalPanel.isVisible = state == ToolCallState.AWAITING_APPROVAL && actions != null
        if (actions != null && approvalPanel.isVisible) {
            approvalPanel.add(JButton("允许一次").apply {
                addActionListener {
                    disableApprovalButtons()
                    actions.onAllowOnce()
                }
            })
            if (!actions.dangerous) {
                approvalPanel.add(JButton("允许此会话").apply {
                    addActionListener {
                        disableApprovalButtons()
                        actions.onAllowSession()
                    }
                })
            }
            approvalPanel.add(JButton("拒绝").apply {
                addActionListener {
                    disableApprovalButtons()
                    actions.onReject()
                }
            })
        }
        approvalPanel.revalidate()
        approvalPanel.repaint()
    }

    private fun disableApprovalButtons() {
        approvalPanel.components.filterIsInstance<JButton>().forEach { it.isEnabled = false }
    }

    /**
     * 使用 SimpleDiff 计算 oldText 和 newText 的行级 Diff 并在 resultArea 中渲染。
     *
     * ADD 行绿色、DEL 行红色、CTX 行灰色，每行前缀对应符号（+/ -/空格）。
     * 对齐 docs/ui/chat.md §四 "Diff 可视化" 要求。
     */
    fun renderDiff(oldText: String, newText: String) {
        val diffLines = SimpleDiff.diff(oldText.lines(), newText.lines())
        val addColor = AppColors.success.toHtmlColor()
        val delColor = AppColors.error.toHtmlColor()
        val ctxColor = AppColors.textSecondary.toHtmlColor()
        val sb = StringBuilder()
        for (line in diffLines) {
            when (line.kind) {
                DiffKind.ADD -> sb.append("<span style='color:$addColor'>+${escapeHtml(line.content)}</span>\n")
                DiffKind.DEL -> sb.append("<span style='color:$delColor'>-${escapeHtml(line.content)}</span>\n")
                DiffKind.CTX -> sb.append("<span style='color:$ctxColor'> ${escapeHtml(line.content)}</span>\n")
            }
        }
        val html =
            "<html><body style='font-family:monospace;font-size:12px;white-space:nowrap;'>${
                sb.toString().replace("\n", "<br>")
            }</body></html>"
        // 创建支持 HTML 渲染的 JLabel 替换 resultComponent（原为 JTextArea），
        // 同时更新字段引用，确保 setResult()/setState() 后续操作与当前展示组件一致
        val diffLabel = JLabel(html).apply {
            isOpaque = true
            background = AppColors.codeBg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }
        resultComponent = diffLabel
        resultScrollPane.setViewportView(resultComponent)
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}
