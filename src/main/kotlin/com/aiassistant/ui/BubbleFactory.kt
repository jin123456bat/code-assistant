package com.aiassistant.ui

import com.aiassistant.MarkdownRenderer
import com.aiassistant.agent_v3.AgentMessage
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * 用户/AI 气泡工厂：单一强调色、hug content、固定间距、真圆角。
 *
 * 尺寸策略已重构为「实时自测量」——见 [ChatBubble]。本工厂只负责：
 *  1. 构造内容组件（用户 HTML JTextPane / AI markdown 容器）；
 *  2. 包进自测量气泡 [ChatBubble]（尺寸由其 getPreferredSize/getMaximumSize 实时算）；
 *  3. 放进左右对齐的 row（X_AXIS + glue）。
 *
 * 不再有 fitWidth / lockRowHeight / refit 等「构造期冻结尺寸」逻辑——
 * 那是用户消息裁字、AI 气泡错位的架构根因。viewport 变化时只需对容器
 * 调用 revalidate()，气泡会按新宽度自动重测。
 */
class BubbleFactory(private val scrollPane: JBScrollPane) {

    companion object {
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

    /** 当前可用宽度（实时）：viewport 优先，其次 scrollPane 自身宽，都不可用返回 0。 */
    private fun availableWidth(): Int {
        val vw = scrollPane.viewport.width
        if (vw > 10) return vw
        val sw = scrollPane.width
        if (sw > 40) return sw - JBUI.scale(20)
        return 0
    }

    fun userBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        // 用户气泡用 HTML JTextPane：视图测高可靠，CJK 不裁字。
        val fg = String.format("#%06X", ChatTheme.userFg.rgb and 0xFFFFFF)
        val esc = htmlEscape(message.content)
        val content = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isOpaque = false
            border = null
            text = "<html><body style='margin:0;padding:0;font-family:sans-serif;" +
                "font-size:13px;color:$fg'>$esc</body></html>"
            caretPosition = 0
        }
        val bubble = ChatBubble(content, ChatTheme.userBg, null, ChatTheme.USER_FRACTION) { availableWidth() }
        val row = rowPanel().apply {
            add(Box.createHorizontalGlue())   // 用户气泡靠右
            add(bubble)
        }
        return Triple(row, bubble, content)
    }

    fun assistantBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        val content = MarkdownRenderer().render(message.content).apply {
            isOpaque = true
            background = ChatTheme.aiBg
        }
        val bubble = ChatBubble(content, ChatTheme.aiBg, ChatTheme.aiBorder, ChatTheme.AI_FRACTION) { availableWidth() }
        val row = rowPanel().apply {
            add(bubble)                        // AI 气泡靠左
            add(Box.createHorizontalGlue())
        }
        return Triple(row, bubble, content)
    }

    /** 转义纯文本为 HTML，换行转 <br>。 */
    private fun htmlEscape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\n", "<br>")

    /** 行容器：X 轴 BoxLayout，左对齐顶部，承载左右对齐 + glue。 */
    private fun rowPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatTheme.GAP_BUBBLE / 2, 0)
    }
}
