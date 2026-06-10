package com.aiassistant.ui

import com.aiassistant.MarkdownRenderer
import com.aiassistant.agent_v3.AgentMessage
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.Border

/**
 * 用户/AI 气泡工厂：单一强调色、hug content、固定间距。
 * 宽度上限取自 BubbleMetrics（不随窗口缩放反复重排——见 ChatToolWindow 接入）。
 */
class BubbleFactory(private val scrollPane: JBScrollPane) {

    companion object {
        const val ABS_CAP = 560

        /**
         * 递归查找容器中第一个 JTextPane 后代。
         * 用于 fitWidth 测量 markdown JPanel 容器的内容宽度。
         *
         * @param component 待搜索的根组件
         * @return 第一个找到的 JTextPane，若无则返回 null
         */
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
            isEditable = false; lineWrap = true; wrapStyleWord = true
            font = ChatTheme.bodyFont
            background = ChatTheme.userBg; foreground = ChatTheme.userFg
            border = null; margin = Insets(0, 0, 0, 0)
        }
        val bubble = bubblePanel(ChatTheme.userBg, ChatTheme.userBg)
        fitWidth(bubble, content)
        bubble.add(content, BorderLayout.CENTER)
        val row = rowPanel().apply {
            add(Box.createHorizontalGlue())
            add(bubble)
        }
        return Triple(row, bubble, content)
    }

    fun assistantBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        val bubble = bubblePanel(ChatTheme.aiBg, ChatTheme.aiBorder)
        val content = MarkdownRenderer().render(message.content).apply { background = ChatTheme.aiBg }
        fitWidth(bubble, content)
        bubble.add(content, BorderLayout.CENTER)
        val row = rowPanel().apply {
            add(bubble)
            add(Box.createHorizontalGlue())
        }
        return Triple(row, bubble, content)
    }

    /** 行容器：X 轴 BoxLayout，承载左右对齐 + glue。 */
    private fun rowPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(2, 0)
    }

    private fun bubblePanel(bg: Color, borderColor: Color): JPanel =
        JPanel(BorderLayout()).apply {
            background = bg
            border = BorderFactory.createCompoundBorder(
                roundedBorder(borderColor),
                JBUI.Borders.empty(ChatTheme.PAD_BUBBLE_V, ChatTheme.PAD_BUBBLE_H)
            )
        }

    /** 宽度上限来源改为 BubbleMetrics；测量逻辑沿用原 constrainContentWidth。 */
    fun fitWidth(bubble: JPanel, contentPane: JComponent) {
        val viewportWidth = scrollPane.viewport.width
        val maxWidth = BubbleMetrics.maxBubbleWidth(viewportWidth, JBUI.scale(ABS_CAP)) - JBUI.scale(20)
        val contentMaxWidth = maxWidth - JBUI.scale(24)
        bubble.maximumSize = Dimension(maxWidth, Int.MAX_VALUE)
        contentPane.maximumSize = Dimension(contentMaxWidth, Int.MAX_VALUE)

        if (contentPane is JTextPane) {
            contentPane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
            val view = contentPane.ui.getRootView(contentPane)
            view.setSize(contentMaxWidth.toFloat(), Short.MAX_VALUE.toFloat())
            val actualHeight = view.getMinimumSpan(javax.swing.text.View.Y_AXIS).toInt()
            val actualWidth = minOf(view.getPreferredSpan(javax.swing.text.View.X_AXIS).toInt() + JBUI.scale(12), contentMaxWidth)
            contentPane.preferredSize = Dimension(actualWidth, maxOf(actualHeight + JBUI.scale(4), JBUI.scale(20)))
            bubble.maximumSize = Dimension(actualWidth + JBUI.scale(24), Int.MAX_VALUE)
        } else if (contentPane is JTextArea) {
            contentPane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
            val pref = contentPane.preferredSize
            val fitW = minOf(pref.width + JBUI.scale(10), contentMaxWidth)
            contentPane.preferredSize = Dimension(fitW, pref.height)
            bubble.maximumSize = Dimension(fitW + JBUI.scale(24), Int.MAX_VALUE)
        } else if (contentPane is JPanel) {
            // markdown 容器（MarkdownRenderer.render() 返回的 BoxLayout Y_AXIS JPanel）
            // 检查是否包含非文本子组件（即代码块 CodeBlockWrapper）
            val hasNonTextChild = contentPane.components.any { child ->
                child is JComponent && child !is JTextPane
            }

            if (hasNonTextChild) {
                // 有代码块子组件时，保守地给予完整宽度上限，避免代码内容被截断。
                // 代码块本身带有水平滚动条，因此使用 contentMaxWidth 作为宽度是合理的。
                contentPane.preferredSize = Dimension(contentMaxWidth, contentPane.preferredSize.height)
                contentPane.maximumSize = Dimension(contentMaxWidth, Int.MAX_VALUE)
                bubble.maximumSize = Dimension(contentMaxWidth + JBUI.scale(24), Int.MAX_VALUE)
            } else {
                // 纯文本路径（无代码块）：找到第一个 JTextPane 子孙，用与 JTextPane 分支
                // 相同的 HTML 视图测量逻辑推算真实内容宽度，使气泡 hug content。
                val innerPane = findFirstTextPane(contentPane)
                if (innerPane != null) {
                    innerPane.size = Dimension(contentMaxWidth, Short.MAX_VALUE.toInt())
                    val view = innerPane.ui.getRootView(innerPane)
                    view.setSize(contentMaxWidth.toFloat(), Short.MAX_VALUE.toFloat())
                    val actualHeight = view.getMinimumSpan(javax.swing.text.View.Y_AXIS).toInt()
                    val actualWidth = minOf(
                        view.getPreferredSpan(javax.swing.text.View.X_AXIS).toInt() + JBUI.scale(12),
                        contentMaxWidth
                    )
                    // 先设置内部 JTextPane 的 preferredSize，BoxLayout 以此布局
                    innerPane.preferredSize = Dimension(actualWidth, maxOf(actualHeight + JBUI.scale(4), JBUI.scale(20)))
                    // 强制容器收口：读取布局后的 preferredSize 高度（包含所有子组件间距），
                    // 再将容器和气泡限制到同一宽度，确保 BorderLayout.CENTER 不拉伸
                    contentPane.invalidate()
                    val containerH = contentPane.preferredSize.height
                    contentPane.preferredSize = Dimension(actualWidth, maxOf(containerH, innerPane.preferredSize.height))
                    contentPane.maximumSize = Dimension(actualWidth, Int.MAX_VALUE)
                    bubble.maximumSize = Dimension(actualWidth + JBUI.scale(24), Int.MAX_VALUE)
                } else {
                    // 容器内无 JTextPane（兜底）：退化为 contentMaxWidth
                    contentPane.preferredSize = Dimension(contentMaxWidth, contentPane.preferredSize.height)
                    contentPane.maximumSize = Dimension(contentMaxWidth, Int.MAX_VALUE)
                    bubble.maximumSize = Dimension(contentMaxWidth + JBUI.scale(24), Int.MAX_VALUE)
                }
            }
        }
    }

    private fun roundedBorder(color: Color, thickness: Int = 1): Border = object : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = BasicStroke(thickness.toFloat())
            g2.drawRoundRect(x, y, w - 1, h - 1, ChatTheme.RADIUS, ChatTheme.RADIUS)
            g2.dispose()
        }
    }
}
