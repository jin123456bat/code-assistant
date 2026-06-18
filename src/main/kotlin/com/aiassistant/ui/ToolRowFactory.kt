package com.aiassistant.ui

import com.aiassistant.agent.AgentMessage
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DocumentContentImpl
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.AbstractBorder

data class ApprovalActions(
    val onAllowOnce: () -> Unit,
    val onAlwaysAllow: () -> Unit,
    val onReject: () -> Unit
)

/**
 * 盲文帧 spinner 标签。
 *
 * 通过 addNotify/removeNotify 管理 Timer 生命周期：
 *  - addNotify()：组件加入层级时启动 Timer
 *  - removeNotify()：组件从层级移除时停止 Timer（防止 rebuild 循环导致 Timer 泄漏）
 */
private class BrailleSpinnerLabel(color: Color) : JLabel() {

    private val frames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private var frameIndex = 0
    private val timer = Timer(90) {
        frameIndex = (frameIndex + 1) % frames.size
        text = frames[frameIndex]
    }

    init {
        font = ChatTheme.metaFont
        foreground = color
        text = frames[0]
        // 固定宽度，防止盲文字符宽度变化引起抖动
        val w = preferredSize.width.coerceAtLeast(ChatTheme.SPINNER_MIN_W)
        minimumSize = Dimension(w, preferredSize.height)
        preferredSize = Dimension(w, preferredSize.height)
        maximumSize = Dimension(w, preferredSize.height)
    }

    override fun updateUI() {
        super.updateUI()
        isOpaque = false
        background = null
    }

    override fun addNotify() {
        super.addNotify()
        if (!timer.isRunning) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}

/**
 * 可交互的停止图标标签。
 *
 * 默认状态：显示与 BrailleSpinnerLabel 相同的盲文 loading 动画
 * 鼠标悬停：变为红色停止图标 ■
 * 点击：触发 onStop 回调，图标变为 ⏹ 并禁用自身防止重复点击
 *
 * Timer 生命周期通过 addNotify/removeNotify 管理，与 BrailleSpinnerLabel 一致。
 */
private class StopIconLabel(
    color: Color,
    private val onStop: () -> Unit
) : JLabel() {

    private val loadingFrames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private val stopIcon = "■"
    private var frameIndex = 0
    private var isHovered = false
    private var isStopped = false

    private val timer = Timer(90) {
        if (!isHovered && !isStopped) {
            frameIndex = (frameIndex + 1) % loadingFrames.size
            text = loadingFrames[frameIndex]
        }
    }

    init {
        font = ChatTheme.metaFont
        foreground = color
        text = loadingFrames[0]
        val w = preferredSize.width.coerceAtLeast(ChatTheme.SPINNER_MIN_W)
        minimumSize = Dimension(w, preferredSize.height)
        preferredSize = Dimension(w, preferredSize.height)
        maximumSize = Dimension(w, preferredSize.height)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                if (isStopped) return
                isHovered = true
                text = stopIcon
                foreground = ChatTheme.error
            }
            override fun mouseExited(e: MouseEvent?) {
                if (isStopped) return
                isHovered = false
                foreground = color
                text = loadingFrames[frameIndex]
            }
            override fun mouseClicked(e: MouseEvent?) {
                if (isStopped) return
                isStopped = true
                timer.stop()
                text = "⏹"
                foreground = ChatTheme.textMuted
                cursor = Cursor.getDefaultCursor()
                onStop()
            }
        })
    }

    override fun updateUI() {
        super.updateUI()
        isOpaque = false
        background = null
    }

    override fun addNotify() {
        super.addNotify()
        if (!timer.isRunning && !isStopped) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }
}

/**
 * 工具/思考行工厂 — 单色折叠行，替代彩色气泡。
 *
 * 设计规范：
 * - 3px 左侧 toolBar 色纵线 + 淡 toolBg 背景
 * - 展开/收起采用 removeAll + rebuild + revalidate/repaint 模式
 * - 所有颜色取自 ChatTheme，不硬编码
 */
class ToolRowFactory(
    private val availableWidth: () -> Int,
    private val project: com.intellij.openapi.project.Project? = null
) {

    private val editorFontSize get() = runCatching { EditorColorsManager.getInstance().globalScheme.editorFontSize }.getOrDefault(14)
    private val toolFont get() = Font(Font.SANS_SERIF, Font.PLAIN, editorFontSize - ChatTheme.TOOL_FONT_OFFSET)
    private val toolFontBold get() = toolFont.deriveFont(Font.BOLD)
    private val toolCodeFont get() = Font(Font.MONOSPACED, Font.PLAIN, editorFontSize - ChatTheme.TOOL_FONT_OFFSET)
    private val thinkFont get() = Font(Font.SANS_SERIF, Font.PLAIN, editorFontSize - ChatTheme.TOOL_FONT_OFFSET)
    private val thinkFontItalic get() = thinkFont.deriveFont(Font.ITALIC)

    // ---- 公开 API ----

    /** 单个工具调用行：name + args 预览，不可折叠 */
    fun singleToolCallRow(name: String, args: String): JPanel {
        val outerRow = outerRow()
        val row = compactRow()
        row.add(arrowLabel(false))
        row.add(hGap(4))
        row.add(toolNameLabel(name))
        val argsPreview = args.replace('\n', ' ').replace('\r', ' ').take(ChatTheme.ARGS_PREVIEW_MAX_CHARS)
            .let { if (args.length > ChatTheme.ARGS_PREVIEW_MAX_CHARS) "$it…" else it }
        if (argsPreview.isNotBlank()) {
            row.add(hGap(6))
            row.add(argsPreviewLabel(argsPreview))
        }
        row.add(Box.createHorizontalGlue())
        outerRow.add(row)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /** 工具调用行：有文本内容时先显示文本，再列出每个 toolCall。紧凑不可折叠 */
    fun toolCallRow(message: AgentMessage): JPanel {
        val toolCalls = message.toolCalls ?: emptyList()
        val outerRow = outerRow()

        if (toolCalls.isEmpty()) {
            // 回退：显示占位行
            val row = compactRow()
            row.add(arrowLabel(false))
            row.add(hGap(4))
            row.add(toolNameLabel("工具调用"))
            row.add(Box.createHorizontalGlue())
            outerRow.add(row)
            outerRow.add(Box.createHorizontalGlue())
            return outerRow
        }

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // 工具调用前的部分文本：用 AI 气泡样式显示
        if (message.content.isNotBlank()) {
            val textRow = compactRow(opaque = false)
            val textLabel = JLabel("<html><body style='width:100%'>${message.content.take(300).replace("\n", "<br>")}</body></html>").apply {
                font = thinkFont
                foreground = ChatTheme.textSecondary
            }
            textRow.add(textLabel)
            textRow.add(Box.createHorizontalGlue())
            container.add(textRow)
        }

        for (tc in toolCalls) {
            val row = compactRow()
            // 箭头占位（工具调用行不可折叠，用静态 ▸）
            row.add(arrowLabel(false))
            row.add(hGap(4))
            row.add(toolNameLabel(tc.name))
            row.add(hGap(6))
            // args 预览：单行，mono，超长截断
            val argsPreview = tc.arguments
                .replace('\n', ' ')
                .replace('\r', ' ')
                .take(ChatTheme.ARGS_PREVIEW_MAX_CHARS)
                .let { if (tc.arguments.length > ChatTheme.ARGS_PREVIEW_MAX_CHARS) "$it…" else it }
            if (argsPreview.isNotBlank()) {
                row.add(argsPreviewLabel(argsPreview))
            }
            row.add(Box.createHorizontalGlue())
            container.add(row)
        }

        outerRow.add(container)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /**
     * 工具结果行：
     * - 失败时（content 以 "错误:" / "错误：" / "Error:" 开头）渲染红色错误卡。
     * - 成功时默认折叠，摘要 "结果 · <toolName>"；展开显示 content（超 2000 chars 截断）。
     */
    fun toolResultRow(message: AgentMessage, approvalActions: ApprovalActions? = null,
                     barColor: java.awt.Color? = null, bgColor: java.awt.Color? = null): JPanel {
        val toolName = message.toolName ?: "tool"
        val rawContent = message.content

        // 检测失败前缀
        val contentTrimmed = rawContent.trimStart()
        val isError = contentTrimmed.startsWith("错误:") ||
                contentTrimmed.startsWith("错误：") ||
                contentTrimmed.startsWith("Error:")

        if (isError) {
            return errorCardRow(toolName, contentTrimmed)
        }

        // 拆分 args 和 result
        val sep = "\n---\n"
        val hasResult = rawContent.contains(sep)
        val argsPart = if (hasResult) rawContent.substringBefore(sep) else rawContent
        val resultPart = if (hasResult) rawContent.substringAfter(sep) else ""

        // 检测 write_file diff 标记：提取旧/新内容，清洗显示文本
        val oldMarker = "[OLD_CONTENT]"
        val endOldMarker = "[/OLD_CONTENT]"
        val newMarker = "[NEW_CONTENT]"
        val endNewMarker = "[/NEW_CONTENT]"
        val isWriteFileDiff = (toolName == "write_file" || toolName == "edit_file") && resultPart.contains(newMarker)
        val diffData: DiffData? = if (isWriteFileDiff) {
            val oldStart = resultPart.indexOf(oldMarker)
            val oldEnd = resultPart.indexOf(endOldMarker)
            val newStart = resultPart.indexOf(newMarker)
            val newEnd = resultPart.indexOf(endNewMarker)
            val old = if (oldStart >= 0 && oldEnd > oldStart) resultPart.substring(oldStart + oldMarker.length, oldEnd).trim() else null
            val new = if (newStart >= 0 && newEnd > newStart) resultPart.substring(newStart + newMarker.length, newEnd).trim() else null
            // 清洗显示文本：移除 diff 标记块，保留文件头信息
            val clean = resultPart
                .replace(Regex("\\[OLD_CONTENT\\].*?\\[/OLD_CONTENT\\]\\s*", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\[NEW_CONTENT\\].*?\\[/NEW_CONTENT\\]\\s*", RegexOption.DOT_MATCHES_ALL), "")
                .trim()
            DiffData(old, new, clean)
        } else null

        val cleanResult = diffData?.cleanDisplay ?: resultPart
        val argsPreview = argsPart.replace('\n', ' ').replace('\r', ' ').take(40)
            .let { if (argsPart.length > 40) "$it…" else it }
        val isTruncated = cleanResult.length > ChatTheme.RESULT_MAX_CHARS
        val displayText = if (isTruncated) cleanResult.take(ChatTheme.RESULT_MAX_CHARS) + "\n… (已截断)" else cleanResult
        val lineCount = cleanResult.count { it == '\n' } + 1

        val outerRow = outerRow()
        val collapsed = AtomicBoolean(true)
        val bubble = leftBarPanel(
            bar = barColor ?: ChatTheme.toolBar,
            bg = bgColor ?: ChatTheme.toolBg
        )

        fun rebuild(isCollapsed: Boolean) {
            bubble.removeAll()

            val infoPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false }
            val running = !hasResult && approvalActions == null
            if (running) {
                infoPanel.add(BrailleSpinnerLabel(ChatTheme.toolBar))
            } else {
                infoPanel.add(arrowLabel(!isCollapsed))
            }
            infoPanel.add(hGap(4))
            infoPanel.add(toolNameLabel(toolName))
            infoPanel.add(hGap(6))
            if (argsPreview.isNotBlank()) {
                infoPanel.add(argsPreviewLabel(argsPreview))
            }

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
            if (approvalActions == null && isCollapsed) {
                val status = if (hasResult) "✓ $lineCount 行" else "执行中..."
                val statusColor = if (hasResult) ChatTheme.textMuted else ChatTheme.toolFg
                rightPanel.add(statusLabel(status).apply { foreground = statusColor })
            }

            val headerRow = JPanel(BorderLayout(0, 0)).apply { isOpaque = false; border = JBUI.Borders.empty(4, 8, 4, 4) }
            headerRow.add(infoPanel, BorderLayout.CENTER)
            headerRow.add(rightPanel, BorderLayout.EAST)

            headerRow.cursor = if (approvalActions == null && !running) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            headerRow.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (approvalActions != null) return
                    if (running) return
                    collapsed.set(!collapsed.get())
                    rebuild(collapsed.get())
                    bubble.revalidate()
                    bubble.repaint()
                }
            })
            bubble.add(headerRow, BorderLayout.NORTH)

            // 审批模式：选项行直接内联到 bubble 中，不创建独立卡片
            if (approvalActions != null) {
                bubble.add(buildApprovalOptions(approvalActions), BorderLayout.CENTER)
                return
            }

            if (!isCollapsed && hasResult) {
                val codePanel = JPanel(BorderLayout()).apply {
                    background = ChatTheme.codeBg
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, ChatTheme.codeBorder),
                        JBUI.Borders.empty(0, 0)
                    )
                }
                // write_file diff 按钮
                if (diffData != null && diffData.oldContent != null && diffData.newContent != null && project != null) {
                    val proj = project
                    val old = diffData.oldContent
                    val new = diffData.newContent
                    val diffBtn = JLabel("  View Diff →  ").apply {
                        font = toolFont
                        foreground = ChatTheme.toolFg
                        isOpaque = true
                        background = ChatTheme.toolBg
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 0, 1, 0, ChatTheme.codeBorder),
                            JBUI.Borders.empty(5, 6, 5, 6)
                        )
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                openDiffView(proj, old, new, toolName)
                            }
                            override fun mouseEntered(e: MouseEvent) { foreground = ChatTheme.accentHover }
                            override fun mouseExited(e: MouseEvent) { foreground = ChatTheme.toolFg }
                        })
                    }
                    codePanel.add(diffBtn, BorderLayout.NORTH)
                }
                val textArea = JTextArea(displayText).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true
                    font = toolCodeFont; background = ChatTheme.codeBg
                    foreground = ChatTheme.textSecondary; border = JBUI.Borders.empty(4, 6)
                }
                if (project != null) FilePathNavigator.attach(textArea, project)
                codePanel.add(textArea, BorderLayout.CENTER)
                // 截断时添加"展开全部"按钮，保留完整 resultPart 引用
                if (isTruncated) {
                    val expandBtn = JLabel("展开全部 ▼").apply {
                        font = ChatTheme.metaFont
                        foreground = ChatTheme.toolFg
                        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        border = JBUI.Borders.empty(4, 6, 4, 6)
                        addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                textArea.text = resultPart  // 完整原文
                                codePanel.remove(this@apply)
                                bubble.revalidate()
                                bubble.repaint()
                            }
                        })
                    }
                    codePanel.add(expandBtn, BorderLayout.SOUTH)
                }
                bubble.add(codePanel, BorderLayout.CENTER)
            }
        }

        rebuild(true)
        outerRow.add(bubble)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /**
     * 错误卡：带红色左栏 + "✕ <toolName> 失败" 标题 + 错误详情（等宽软换行）。
     * 仅当 toolResultRow 检测到失败前缀时调用。
     */
    private fun errorCardRow(toolName: String, errorContent: String): JPanel {
        val outerRow = outerRow()

        // 错误卡容器：红色左栏边框，浅红背景
        val card = JPanel(BorderLayout()).apply {
            background = ChatTheme.errorCardBg
            border = LeftBarBorder(ChatTheme.error, 3, 7)
        }

        // 标题行
        val headerRow = compactRow(opaque = false)
        val titleLabel = JLabel("✕ $toolName 失败").apply {
            font = toolFontBold
            foreground = ChatTheme.error
        }
        headerRow.add(titleLabel)
        headerRow.add(Box.createHorizontalGlue())
        card.add(headerRow, BorderLayout.NORTH)

        // 错误详情区（等宽软换行，codeBg 面板）
        val textArea = JTextArea(errorContent).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = toolCodeFont
            background = ChatTheme.codeBg
            foreground = ChatTheme.error
            border = JBUI.Borders.empty(4, 6)
        }
        val codePanel = JPanel(BorderLayout()).apply {
            background = ChatTheme.codeBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ChatTheme.codeBorder),
                JBUI.Borders.empty(0, 0)
            )
            add(textArea, BorderLayout.CENTER)
        }
        card.add(codePanel, BorderLayout.CENTER)

        outerRow.add(card)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /** 流式工具行引用，供外部原地更新 */
    data class StreamingToolRow(
        val outerRow: JPanel,
        val contentArea: JPanel,
        val collapsed: java.util.concurrent.atomic.AtomicBoolean,
        val stopIconLabel: JComponent? = null  // 仅 task 工具非空（StopIconLabel 实例），供外部清理引用
    )

    /** 执行中行：带动态盲文 spinner，不可折叠 */
    fun runningRow(toolName: String): JPanel {
        val outerRow = outerRow()
        val row = compactRow()
        row.add(hGap(2))
        val spinner = BrailleSpinnerLabel(ChatTheme.toolBar)
        row.add(spinner)
        row.add(hGap(4))
        val label = JLabel("执行中 · $toolName").apply {
            font = toolFont
            foreground = ChatTheme.toolFg
        }
        row.add(label)
        row.add(Box.createHorizontalGlue())
        outerRow.add(row)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /**
     * 工具执行期间的流式输出行：可折叠，**默认折叠**。
     * 有实时输出的工具（task、execute_command）内容动态填入，用户点击展开查看。
     * @param isTask 是否为子 Agent（task 用紫色，普通工具用蓝色）
     * @param onStop task 工具停止回调，isTask=true 时传入以启用可交互停止图标
     */
    fun streamingToolRow(toolName: String, isTask: Boolean = false, onStop: (() -> Unit)? = null): StreamingToolRow {
        val barColor = if (isTask) ChatTheme.agentBar else ChatTheme.toolBar
        val bgColor = if (isTask) ChatTheme.agentBg else ChatTheme.toolBg
        val collapsed = java.util.concurrent.atomic.AtomicBoolean(true)  // 默认折叠

        val chevron = JLabel("▸").apply {  // 折叠态箭头
            font = ChatTheme.metaFont
            foreground = barColor
            border = JBUI.Borders.empty(0, 6, 0, 4)
        }
        // task 工具使用可交互停止图标，普通工具使用纯动画 spinner
        val spinnerOrStop: JComponent = if (isTask && onStop != null) {
            StopIconLabel(barColor, onStop)
        } else {
            BrailleSpinnerLabel(barColor)
        }
        val label = JLabel("执行中 · $toolName").apply {
            font = toolFont
            foreground = ChatTheme.toolFg
        }
        val headerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(chevron)
            add(spinnerOrStop)
            add(hGap(4))
            add(label)
            add(Box.createHorizontalGlue())
        }

        val contentArea = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            background = bgColor
            border = JBUI.Borders.empty(4, 0, 6, 0)
        }

        val leftBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = bgColor
            border = LeftBarBorder(barColor, 3, 7)
            add(headerRow, BorderLayout.NORTH)
            add(contentArea, BorderLayout.CENTER)
            // 点击标题折叠/展开
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    collapsed.set(!collapsed.get())
                    applyStreamingCollapsed(this@apply, collapsed.get(), chevron, contentArea)
                }
            })
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        val outerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(leftBar)
            add(Box.createHorizontalGlue())
        }

        return StreamingToolRow(outerRow, contentArea, collapsed,
            stopIconLabel = spinnerOrStop as? StopIconLabel)
    }

    /** 切换流式工具行的折叠状态 */
    fun applyStreamingCollapsed(bar: JPanel, collapsed: Boolean, chevron: JLabel, contentArea: JComponent) {
        chevron.text = if (collapsed) "▸" else "▾"
        contentArea.isVisible = !collapsed
        bar.revalidate()
        bar.repaint()
    }

    /** 子代理工具执行中行：agentBar 色 spinner + 工具名，默认折叠，展开显示状态提示 */
    fun subAgentToolRunningRow(toolName: String): JPanel {
        val collapsed = java.util.concurrent.atomic.AtomicBoolean(true)
        val chevron = JLabel("▸").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.agentBar
            border = JBUI.Borders.empty(0, 12, 0, 4)
        }
        val spinner = BrailleSpinnerLabel(ChatTheme.agentBar)
        val label = JLabel(toolName).apply {
            font = toolFont
            foreground = ChatTheme.textSecondary
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(chevron)
            add(spinner)
            add(hGap(4))
            add(label)
            add(Box.createHorizontalGlue())
        }
        val statusArea = JTextArea("执行中，等待子代理返回结果…").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.textSecondary
            background = ChatTheme.agentBg
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(2, 16, 4, 8)
            isVisible = false
        }
        val leftBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = ChatTheme.agentBg
            border = LeftBarBorder(ChatTheme.agentBar, 2, 4)
            add(header, BorderLayout.NORTH)
            add(statusArea, BorderLayout.CENTER)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    collapsed.set(!collapsed.get())
                    chevron.text = if (collapsed.get()) "▸" else "▾"
                    statusArea.isVisible = !collapsed.get()
                    revalidate()
                    repaint()
                }
            })
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(leftBar)
            add(Box.createHorizontalGlue())
        }
        return outer
    }

    /** 子代理工具结果行：agentBar 色左栏，默认折叠，可展开查看结果 */
    fun subAgentToolResultRow(toolName: String, content: String): JPanel {
        val collapsed = java.util.concurrent.atomic.AtomicBoolean(true)
        val chevron = JLabel("▸").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.agentBar
            border = JBUI.Borders.empty(0, 12, 0, 4)
        }
        val label = JLabel("结果 · $toolName").apply {
            font = toolFont
            foreground = ChatTheme.toolFg
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(chevron)
            add(label)
            add(Box.createHorizontalGlue())
        }
        val textArea = JTextArea(content).apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.textSecondary
            background = ChatTheme.agentBg
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(2, 16, 4, 8)
            isVisible = false
        }
        val leftBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = ChatTheme.agentBg
            border = LeftBarBorder(ChatTheme.agentBar, 2, 4)
            add(header, BorderLayout.NORTH)
            add(textArea, BorderLayout.CENTER)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    collapsed.set(!collapsed.get())
                    chevron.text = if (collapsed.get()) "▸" else "▾"
                    textArea.isVisible = !collapsed.get()
                    revalidate()
                    repaint()
                }
            })
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(leftBar)
            add(Box.createHorizontalGlue())
        }
        return outer
    }

    /**
     * 思考行：默认折叠（低调 textMuted 斜体，前 ~100 chars）；
     * 展开后用 bodyFont 显示全文。无彩色气泡背景。
     * @param initiallyExpanded 流式展示时传 true，让用户实时看到思考过程
     * @param streaming 流式接收中时传 true，展开标题显示"思考中..."
     */
    /** @param textAreaRef 用于外部获取内部 JTextArea 引用，避免递归查找 */
    fun thinkingRow(content: String, initiallyExpanded: Boolean = false, streaming: Boolean = false, textAreaRef: java.util.concurrent.atomic.AtomicReference<JTextArea>? = null): JPanel {
        val summary = content.lines().take(2).joinToString(" ").take(ChatTheme.THINKING_PREVIEW_MAX_CHARS)
            .let { if (content.length > ChatTheme.THINKING_PREVIEW_MAX_CHARS) "$it…" else it }

        val outerRow = outerRow()
        val collapsed = AtomicBoolean(!initiallyExpanded)

        // 思考行使用更轻量的容器（不带左栏边框），通过颜色暗示
        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 0)
        }

        // headerRow 和 textArea 只创建一次，展开/收起时仅切换可见性 + 更新 header 内容
        val headerLabel = JLabel().apply {
            font = thinkFontItalic
            foreground = ChatTheme.textMuted
        }

        // 自测量 textArea：只创建一次，通过 visibility 切换
        val textArea = object : JTextArea(content) {
            override fun getPreferredSize(): Dimension {
                val w = availableWidth() - JBUI.scale(ChatTheme.TOOL_PREVIEW_DEDUCT)
                if (w > 10) size = Dimension(w, Short.MAX_VALUE.toInt())
                return super.getPreferredSize()
            }
        }.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = thinkFont
            isOpaque = false
            border = JBUI.Borders.empty(4, 20, 4, 0)
            foreground = ChatTheme.textSecondary
        }
        textAreaRef?.set(textArea)

        val headerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(arrowLabel(initiallyExpanded))
            add(hGap(4))
            add(headerLabel)
            add(Box.createHorizontalGlue())
        }

        fun applyCollapsedState(isCollapsed: Boolean) {
            val arrow = headerRow.getComponent(0) as? JLabel
            arrow?.text = if (isCollapsed) "▸" else "▾"
            headerLabel.text = if (isCollapsed) summary else (if (streaming) "思考中..." else "思考过程")
            textArea.isVisible = !isCollapsed
        }

        headerRow.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                collapsed.set(!collapsed.get())
                applyCollapsedState(collapsed.get())
                container.revalidate()
                container.repaint()
            }
        })

        container.add(headerRow, BorderLayout.NORTH)
        container.add(textArea, BorderLayout.CENTER)

        applyCollapsedState(!initiallyExpanded)
        outerRow.add(container)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    // ---- 私有工具方法 ----

    /** 外层行面板：X 轴 BoxLayout，不透明，统一间距，左对齐 */
    /** 外层行面板：X 轴 BoxLayout，hug content 高度（同 ChatBubble 不拉伸策略） */
    private fun outerRow(): JPanel = object : JPanel() {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatTheme.GAP_BUBBLE / 2, 0)
    }

    /**
     * 带左栏边框的容器面板：
     * - 左侧 3px toolBar 色竖线
     * - 淡 toolBg 背景
     * - 右侧圆角约 7px
     */
    private fun leftBarPanel(bar: java.awt.Color = ChatTheme.toolBar,
                             bg: java.awt.Color = ChatTheme.toolBg): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false  // 使用半透明背景，必须关闭 opaque 避免覆盖上层组件
        background = bg
        border = LeftBarBorder(bar, 3, 7)
    }

    /** 紧凑行：X 轴 BoxLayout，可选透明，内边距 4-8 */
    private fun compactRow(opaque: Boolean = false): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = opaque
        border = JBUI.Borders.empty(4, 8)
    }

    /** 展开/收起箭头标签 */
    private fun arrowLabel(expanded: Boolean): JLabel = JLabel(if (expanded) "▾" else "▸").apply {
        font = toolFont
        foreground = ChatTheme.toolFg
        // 宽度固定，防止切换时闪烁
        preferredSize = Dimension(ChatTheme.ARROW_WIDTH, preferredSize.height)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    /** 工具名称标签：加粗，toolFg 颜色 */
    private fun toolNameLabel(name: String): JLabel = JLabel(name).apply {
        font = toolFontBold
        foreground = ChatTheme.toolFg
    }

    /** Args 预览标签：等宽，textMuted，单行截断 */
    private fun argsPreviewLabel(text: String): JLabel = JLabel(text).apply {
        font = ChatTheme.codeFont.deriveFont(toolFont.size.toFloat())
        foreground = ChatTheme.textMuted
    }

    /** 右侧状态标签（如 "✓ 62 行"） */
    private fun statusLabel(text: String): JLabel = JLabel(text).apply {
        font = toolFont
        foreground = ChatTheme.textMuted
        border = JBUI.Borders.empty(0, 0, 0, 4)
    }

    /** 水平间隔 */
    private fun hGap(px: Int): Component = Box.createRigidArea(Dimension(px, 0))

    // ---- 审批选项（内联到 tool 结果行） ----

    /**
     * 构建审批选项行列表，直接内联到 tool 结果 bubble 的 CENTER 区域。
     * 三个选项：允许本次 / 始终允许 / 拒绝，点击即提交。
     */
    private fun buildApprovalOptions(approvalActions: ApprovalActions): JPanel {
        val optionList = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(2, 8, 4, 6)
        }

        var confirmed = false

        val options = listOf(
            "❯  允许本次" to approvalActions.onAllowOnce,
            "❯  始终允许" to approvalActions.onAlwaysAllow,
            "❯  拒绝" to approvalActions.onReject
        )

        options.forEachIndexed { idx, (text, action) ->
            val isDefault = idx == 0
            var hovered = isDefault

            val row = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    if (hovered && !confirmed) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = ChatTheme.toolBg
                        g2.fillRoundRect(0, 0, width, height, ChatTheme.RADIUS_INNER, ChatTheme.RADIUS_INNER)
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
                override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 4, 4, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }

            val lbl = JLabel(text).apply {
                font = ChatTheme.metaFont
                foreground = if (isDefault) ChatTheme.textPrimary else ChatTheme.textSecondary
            }
            row.add(lbl, BorderLayout.CENTER)

            row.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (confirmed) return
                    confirmed = true
                    val isReject = idx == 2
                    lbl.text = if (isReject) "✕  $text" else "✓  $text"
                    lbl.foreground = if (isReject) ChatTheme.rejectedFg else ChatTheme.doneCheck
                    optionList.components.forEach { c ->
                        c.isEnabled = false
                        c.cursor = Cursor.getDefaultCursor()
                    }
                    optionList.repaint()
                    action()
                }
                override fun mouseEntered(e: MouseEvent) {
                    if (!confirmed) { hovered = true; row.repaint() }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (!confirmed) { hovered = isDefault; row.repaint() }
                }
            })

            optionList.add(row)
        }

        return optionList
    }

    // ---- 边框实现 ----

    /**
     * 左栏边框：
     * - 左侧绘制 [barWidth] px 宽的 [barColor] 实心矩形
     * - 右侧三个角保留圆角 [cornerRadius] px
     * - 内边距：左 barWidth + 8，其余 6
     */
    private inner class LeftBarBorder(
        private val barColor: Color,
        private val barWidth: Int,
        private val cornerRadius: Int
    ) : AbstractBorder() {

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 绘制圆角背景（整体），颜色由面板 background 决定，这里只处理边框效果
            // 左侧竖线：实心矩形
            g2.color = barColor
            g2.fillRect(x, y + cornerRadius, barWidth, h - cornerRadius * 2)
            // 左上角填充（非圆角）
            g2.fillRect(x, y, barWidth, cornerRadius)
            // 左下角填充（非圆角）
            g2.fillRect(x, y + h - cornerRadius, barWidth, cornerRadius)

            g2.dispose()
        }

        override fun getBorderInsets(c: Component): Insets =
            Insets(6, barWidth + 8, 6, 8)

        override fun getBorderInsets(c: Component, insets: Insets): Insets {
            insets.set(6, barWidth + 8, 6, 8)
            return insets
        }

        override fun isBorderOpaque(): Boolean = false
    }
}

/** write_file 工具的 diff 数据：旧内容、新内容、清洗后的显示文本 */
private data class DiffData(
    val oldContent: String?,
    val newContent: String?,
    val cleanDisplay: String
)

/** 打开 IntelliJ diff 视图，对比 write_file 的旧/新内容 */
private fun openDiffView(project: Project, oldContent: String, newContent: String, title: String) {
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(title)
    val oldDoc = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(oldContent)
    val newDoc = com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(newContent)
    val oldContentImpl = DocumentContentImpl(project, oldDoc, fileType)
    val newContentImpl = DocumentContentImpl(project, newDoc, fileType)
    val request = SimpleDiffRequest("$title — 改动对比", oldContentImpl, newContentImpl, "写入前", "写入后")
    DiffManager.getInstance().showDiff(project, request)
    // EditorFactory.createDocument() 内部由 WeakHashMap 跟踪，diff 视图关闭后无引用时自动 GC
}
