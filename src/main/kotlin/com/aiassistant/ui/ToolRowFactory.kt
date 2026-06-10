package com.aiassistant.ui

import com.aiassistant.agent_v3.AgentMessage
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.border.AbstractBorder

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
        val w = preferredSize.width.coerceAtLeast(14)
        minimumSize = Dimension(w, preferredSize.height)
        preferredSize = Dimension(w, preferredSize.height)
        maximumSize = Dimension(w, preferredSize.height)
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
 * 工具/思考行工厂 — 单色折叠行，替代彩色气泡。
 *
 * 设计规范：
 * - 3px 左侧 toolBar 色纵线 + 淡 toolBg 背景
 * - 展开/收起采用 removeAll + rebuild + revalidate/repaint 模式
 * - 所有颜色取自 ChatTheme，不硬编码
 */
class ToolRowFactory {

    // ---- 公开 API ----

    /** 工具调用行：列出每个 toolCall 的 name + args 预览，紧凑不可折叠 */
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

        // 每个 toolCall 独立渲染为一行
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
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
                .take(120)
                .let { if (tc.arguments.length > 120) "$it…" else it }
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
    fun toolResultRow(message: AgentMessage): JPanel {
        val toolName = message.toolName ?: "tool"

        // 检测失败前缀（中文全角/半角冒号 + 英文 Error:）
        val contentTrimmed = message.content.trimStart()
        val isError = contentTrimmed.startsWith("错误:") ||
                contentTrimmed.startsWith("错误：") ||
                contentTrimmed.startsWith("Error:")

        if (isError) {
            return errorCardRow(toolName, contentTrimmed)
        }

        // ---- 正常折叠结果行 ----
        val resultText = message.content.let {
            if (it.length > 2000) it.take(2000) + "\n… (已截断)" else it
        }
        // 估算行数：换行数 + 1，用于状态提示
        val lineCount = message.content.count { it == '\n' } + 1

        val outerRow = outerRow()
        val collapsed = AtomicBoolean(true)

        // bubble 是带左栏边框的容器
        val bubble = leftBarPanel()

        fun rebuild(isCollapsed: Boolean) {
            bubble.removeAll()
            val headerRow = compactRow(opaque = false)
            headerRow.background = null
            headerRow.add(arrowLabel(!isCollapsed))
            headerRow.add(hGap(4))
            headerRow.add(toolNameLabel("结果 · $toolName"))
            headerRow.add(Box.createHorizontalGlue())
            if (isCollapsed) {
                // 状态：行数
                headerRow.add(statusLabel("✓ $lineCount 行"))
            }
            headerRow.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            headerRow.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    collapsed.set(!collapsed.get())
                    rebuild(collapsed.get())
                    bubble.revalidate()
                    bubble.repaint()
                }
            })
            bubble.add(headerRow, BorderLayout.NORTH)

            if (!isCollapsed) {
                val textArea = JTextArea(resultText).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    font = ChatTheme.codeFont
                    background = ChatTheme.codeBg
                    foreground = ChatTheme.textSecondary
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
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
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
            font = ChatTheme.codeFont
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

    /** 执行中行：带动态盲文 spinner，不可折叠 */
    fun runningRow(toolName: String): JPanel {
        val outerRow = outerRow()
        val row = compactRow()
        row.add(hGap(2))
        // 动画 spinner：addNotify 启动 Timer，removeNotify 停止（无泄漏）
        val spinner = BrailleSpinnerLabel(ChatTheme.toolBar)
        row.add(spinner)
        row.add(hGap(4))
        val label = JLabel("执行中 · $toolName").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.toolFg
        }
        row.add(label)
        row.add(Box.createHorizontalGlue())
        outerRow.add(row)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    /**
     * 思考行：默认折叠（低调 textMuted 斜体，前 ~100 chars）；
     * 展开后用 bodyFont 显示全文。无彩色气泡背景。
     */
    fun thinkingRow(content: String): JPanel {
        val summary = content.lines().take(2).joinToString(" ").take(100)
            .let { if (content.length > 100) "$it…" else it }

        val outerRow = outerRow()
        val collapsed = AtomicBoolean(true)

        // 思考行使用更轻量的容器（不带左栏边框），通过颜色暗示
        val container = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 2, 0)
        }

        fun rebuild(isCollapsed: Boolean) {
            container.removeAll()
            val headerRow = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            headerRow.add(arrowLabel(!isCollapsed))
            headerRow.add(hGap(4))

            if (isCollapsed) {
                val lbl = JLabel(summary).apply {
                    font = ChatTheme.metaFont.deriveFont(Font.ITALIC)
                    foreground = ChatTheme.textMuted
                }
                headerRow.add(lbl)
            } else {
                val lbl = JLabel("思考过程").apply {
                    font = ChatTheme.metaFont.deriveFont(Font.ITALIC)
                    foreground = ChatTheme.textMuted
                }
                headerRow.add(lbl)
            }
            headerRow.add(Box.createHorizontalGlue())

            // 点击整行切换折叠状态
            headerRow.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    collapsed.set(!collapsed.get())
                    rebuild(collapsed.get())
                    container.revalidate()
                    container.repaint()
                }
            })

            container.add(headerRow, BorderLayout.NORTH)

            if (!isCollapsed) {
                val textArea = JTextArea(content).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    font = ChatTheme.bodyFont
                    isOpaque = false
                    border = JBUI.Borders.empty(4, 20, 4, 0)
                    foreground = ChatTheme.textSecondary
                }
                container.add(textArea, BorderLayout.CENTER)
            }
        }

        rebuild(true)
        outerRow.add(container)
        outerRow.add(Box.createHorizontalGlue())
        return outerRow
    }

    // ---- 私有工具方法 ----

    /** 外层行面板：X 轴 BoxLayout，不透明，统一间距 */
    private fun outerRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(1, 0)
    }

    /**
     * 带左栏边框的容器面板：
     * - 左侧 3px toolBar 色竖线
     * - 淡 toolBg 背景
     * - 右侧圆角约 7px
     */
    private fun leftBarPanel(): JPanel = JPanel(BorderLayout()).apply {
        background = ChatTheme.toolBg
        border = LeftBarBorder(ChatTheme.toolBar, 3, 7)
    }

    /** 紧凑行：X 轴 BoxLayout，可选透明，内边距 4-8 */
    private fun compactRow(opaque: Boolean = false): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = opaque
        border = JBUI.Borders.empty(4, 8)
    }

    /** 展开/收起箭头标签 */
    private fun arrowLabel(expanded: Boolean): JLabel = JLabel(if (expanded) "▾" else "▸").apply {
        font = ChatTheme.metaFont
        foreground = ChatTheme.toolFg
        // 宽度固定，防止切换时闪烁
        preferredSize = Dimension(10, preferredSize.height)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    /** 工具名称标签：加粗，toolFg 颜色 */
    private fun toolNameLabel(name: String): JLabel = JLabel(name).apply {
        font = ChatTheme.metaFont.deriveFont(Font.BOLD)
        foreground = ChatTheme.toolFg
    }

    /** Args 预览标签：等宽，textMuted，单行截断 */
    private fun argsPreviewLabel(text: String): JLabel = JLabel(text).apply {
        font = ChatTheme.codeFont.deriveFont(ChatTheme.metaFont.size.toFloat())
        foreground = ChatTheme.textMuted
    }

    /** 右侧状态标签（如 "✓ 62 行"） */
    private fun statusLabel(text: String): JLabel = JLabel(text).apply {
        font = ChatTheme.metaFont
        foreground = ChatTheme.textMuted
        border = JBUI.Borders.empty(0, 0, 0, 4)
    }

    /** 水平间隔 */
    private fun hGap(px: Int): Component = Box.createRigidArea(Dimension(px, 0))

    // ---- 左栏边框实现 ----

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
