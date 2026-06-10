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

    companion object { const val ABS_CAP = 560 }

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
    fun rowPanel(): JPanel = JPanel().apply {
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
