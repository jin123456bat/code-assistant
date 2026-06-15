package com.aiassistant.ui

import com.aiassistant.MarkdownRenderer
import com.aiassistant.agent.AgentMessage
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
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
class BubbleFactory(
    private val scrollPane: JBScrollPane,
    private val project: com.intellij.openapi.project.Project? = null
) {

    private val editorFontSize get() = runCatching { EditorColorsManager.getInstance().globalScheme.editorFontSize }.getOrDefault(14)

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
        if (sw > 40) return sw - JBUI.scale(ChatTheme.BUBBLE_WIDTH_DEDUCT)
        return 0
    }

    fun userBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        return buildUserBubble(message, null)
    }

    /** 带底部 footer（如引用文件 chips）的用户气泡。footer 为 null 时退化为普通用户气泡。 */
    fun userBubbleWithFooter(message: AgentMessage, footer: JComponent): Triple<JPanel, JPanel, JComponent> {
        return buildUserBubble(message, footer)
    }

    private fun buildUserBubble(message: AgentMessage, footer: JComponent?): Triple<JPanel, JPanel, JComponent> {
        val fg = String.format("#%06X", ChatTheme.userFg.rgb and 0xFFFFFF)
        val esc = htmlEscape(message.content)
        val textPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isOpaque = false
            border = null
            text = "<html><body style='margin:0;padding:0;font-family:sans-serif;" +
                "font-size:${editorFontSize}px;color:$fg'>$esc</body></html>"
            caretPosition = 0
        }
        val content: JComponent = if (footer != null) {
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(textPane)
                add(footer)
            }
        } else {
            textPane
        }
        val bubble = ChatBubble(content, ChatTheme.userBg, null, ChatTheme.USER_FRACTION) { availableWidth() }
        val row = rowPanel().apply {
            add(Box.createHorizontalGlue())   // 用户气泡靠右
            add(bubble)
        }
        return Triple(row, bubble, content)
    }

    fun assistantBubble(message: AgentMessage): Triple<JPanel, JPanel, JComponent> {
        val content = MarkdownRenderer().render(message.content, project).apply {
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

    /** 行容器：X 轴 BoxLayout，宽度可拉伸（让 glue 推气泡对齐），高度 hug content */
    private fun rowPanel(): JPanel = object : JPanel() {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatTheme.GAP_BUBBLE / 2, 0)
    }
}
