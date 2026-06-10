package com.aiassistant.ui

import com.aiassistant.MarkdownRenderer
import com.aiassistant.agent_v3.AgentMessage
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * 用户/AI 气泡工厂：单一强调色、hug content、固定间距、真圆角。
 *
 * 关键设计（修复历史 bug）：
 * 1. 气泡用自绘圆角面板 [RoundedPanel]（isOpaque=false + paintComponent 填圆角），
 *    避免 opaque JPanel 先填方形背景再描圆边导致的"方角实心块"。
 * 2. row 与 bubble 都限制 maximumSize.height = 自身 preferredSize.height，
 *    否则外层 Y_AXIS BoxLayout 会把每行纵向拉伸成大空盒。
 * 3. fitWidth 在 content 已 add 进 bubble 之后调用，保证 HTML 视图测量可靠。
 */
class BubbleFactory(private val scrollPane: JBScrollPane) {

    companion object {
        const val ABS_CAP = 560
        const val USER_FRACTION = 0.80   // 用户气泡最大 80% 宽
        const val AI_FRACTION = 1.0      // AI 气泡最大 100% 宽

        /** 递归查找容器中第一个 JTextPane 后代（用于测量 markdown 容器内容宽度）。 */
        fun findFirstTextPane(component: JComponent): JTextPane? {
            if (component is JTextPane) return component
            for (child in component.components) {
                if (child is JComponent) {
                    val found = findFirstTextPane(child)
                    if (found != null) return found
                }
            }
            return null
        }
    }

    fun userBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        val content = JTextArea(message.content).apply {
            isEditable = false
            font = ChatTheme.bodyFont
            isOpaque = false
            foreground = ChatTheme.userFg
            border = null; margin = Insets(0, 0, 0, 0)
        }
        val bubble = roundedBubble(ChatTheme.userBg, null)
        bubble.add(content, BorderLayout.CENTER)
        fitWidth(bubble, content, USER_FRACTION)
        val row = rowPanel().apply {
            add(Box.createHorizontalGlue())   // 用户气泡靠右
            add(bubble)
        }
        lockRowHeight(row, bubble)
        return Triple(row, bubble, content)
    }

    fun assistantBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        val content = MarkdownRenderer().render(message.content).apply { isOpaque = false }
        val bubble = roundedBubble(ChatTheme.aiBg, ChatTheme.aiBorder)
        bubble.add(content, BorderLayout.CENTER)
        fitWidth(bubble, content, AI_FRACTION)
        val row = rowPanel().apply {
            add(bubble)                        // AI 气泡靠左
            add(Box.createHorizontalGlue())
        }
        lockRowHeight(row, bubble)
        return Triple(row, bubble, content)
    }

    /** 行容器：X 轴 BoxLayout，左对齐顶部，承载左右对齐 + glue。 */
    private fun rowPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatTheme.GAP_BUBBLE / 2, 0)
    }

    /**
     * 限制 row 的最大高度为其 preferredSize 高度，防止外层 Y_AXIS BoxLayout 纵向拉伸成空盒。
     * 在 fitWidth 之后调用（此时 bubble/content 的 preferredSize 已确定）。
     */
    private fun lockRowHeight(row: JPanel, bubble: JPanel) {
        val h = bubble.preferredSize.height
        bubble.maximumSize = Dimension(bubble.maximumSize.width, h)
        row.maximumSize = Dimension(Int.MAX_VALUE, h)
        row.preferredSize = Dimension(row.preferredSize.width, h)
    }

    /**
     * 自绘圆角气泡面板：背景填圆角矩形，可选描边。
     * isOpaque=false 让方形角落透出父背景，只有圆角区域被填充。
     */
    private fun roundedBubble(bg: Color, borderColor: Color?): JPanel {
        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = ChatTheme.RADIUS * 2
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, arc, arc)
                if (borderColor != null) {
                    g2.color = borderColor
                    g2.stroke = BasicStroke(1f)
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                }
                g2.dispose()
                super.paintComponent(g)
            }
        }
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(ChatTheme.PAD_BUBBLE_V, ChatTheme.PAD_BUBBLE_H)
        panel.alignmentY = Component.TOP_ALIGNMENT
        return panel
    }

    /**
     * 测量内容真实尺寸并约束 bubble 宽度，使气泡 hug content（不撑满）。
     * 宽度上限 = viewport * fraction（用户 0.8 / AI 1.0），封顶 ABS_CAP。
     * 要求 content 已经 add 进 bubble。
     */
    fun fitWidth(bubble: JPanel, contentPane: JComponent, fraction: Double = AI_FRACTION) {
        val viewportWidth = scrollPane.viewport.width
        val maxWidth = BubbleMetrics.maxBubbleWidth(viewportWidth, JBUI.scale(ABS_CAP), fraction) - JBUI.scale(20)
        val padW = JBUI.scale(ChatTheme.PAD_BUBBLE_H) * 2   // 气泡左右内边距
        val contentMaxWidth = maxWidth - padW

        val (actualW, actualH) = measureContent(contentPane, contentMaxWidth)

        contentPane.preferredSize = Dimension(actualW, actualH)
        contentPane.maximumSize = Dimension(actualW, actualH)
        val bubbleW = actualW + padW
        val bubbleH = actualH + JBUI.scale(ChatTheme.PAD_BUBBLE_V) * 2
        bubble.preferredSize = Dimension(bubbleW, bubbleH)
        bubble.maximumSize = Dimension(bubbleW, bubbleH)
    }

    /** 测量内容在给定最大宽度下的真实 (宽, 高)。 */
    private fun measureContent(contentPane: JComponent, contentMaxWidth: Int): Pair<Int, Int> {
        when (contentPane) {
            is JTextArea -> {
                // 关键：先用 lineWrap=false 量内容自然宽度（对中文/CJK 准确，不裁字）。
                // 自然宽 ≤ 上限 → 用自然宽、单行不换行；
                // 自然宽 > 上限 → 锁定为上限并开启换行，按上限重新量高度。
                contentPane.lineWrap = false
                contentPane.wrapStyleWord = false
                contentPane.size = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val naturalW = contentPane.preferredSize.width
                val w = if (naturalW <= contentMaxWidth) maxOf(naturalW, JBUI.scale(12)) else contentMaxWidth
                if (naturalW > contentMaxWidth) {
                    contentPane.lineWrap = true
                    contentPane.wrapStyleWord = true
                }
                // 用确定的宽度重新量高度，避免单行/换行下高度被低估导致文字纵向裁切
                contentPane.size = Dimension(w, Short.MAX_VALUE.toInt())
                val h = contentPane.preferredSize.height
                return w to h
            }
            is JTextPane -> return measureTextPane(contentPane, contentMaxWidth)
            is JPanel -> {
                // markdown 容器：含代码块时给满宽，纯文本时按内部 JTextPane hug
                val hasNonText = contentPane.components.any { it is JComponent && it !is JTextPane }
                if (hasNonText) {
                    contentPane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
                    return contentMaxWidth to contentPane.preferredSize.height
                }
                val inner = findFirstTextPane(contentPane)
                    ?: return contentMaxWidth to contentPane.preferredSize.height
                val (w, h) = measureTextPane(inner, contentMaxWidth)
                inner.preferredSize = Dimension(w, h)
                inner.maximumSize = Dimension(w, h)
                contentPane.size = Dimension(w, Short.MAX_VALUE.toInt())
                return w to maxOf(h, contentPane.preferredSize.height)
            }
            else -> {
                contentPane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
                val pref = contentPane.preferredSize
                return minOf(pref.width, contentMaxWidth) to pref.height
            }
        }
    }

    /** 用 HTML 根视图测量 JTextPane 在给定宽度下的真实内容宽高。 */
    private fun measureTextPane(pane: JTextPane, contentMaxWidth: Int): Pair<Int, Int> {
        pane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
        val view = pane.ui.getRootView(pane)
        view.setSize(contentMaxWidth.toFloat(), Short.MAX_VALUE.toFloat())
        val h = view.getMinimumSpan(javax.swing.text.View.Y_AXIS).toInt()
        val w = minOf(
            view.getPreferredSpan(javax.swing.text.View.X_AXIS).toInt() + JBUI.scale(4),
            contentMaxWidth
        )
        return maxOf(w, JBUI.scale(20)) to maxOf(h + JBUI.scale(4), JBUI.scale(20))
    }
}
