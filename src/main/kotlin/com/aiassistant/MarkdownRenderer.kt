package com.aiassistant

import com.aiassistant.ui.ChatTheme
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

/**
 * Renders markdown content as a Swing JPanel.
 * Uses org.jetbrains:markdown for parsing and generates
 * styled HTML. Code blocks get syntax-highlighted EditorTextField panels.
 *
 * render() 的输出是一个多分段容器：
 *   - 普通 Markdown 文本段 → JTextPane (HTML)
 *   - 围栏代码块 (``` ... ```) → CodeBlockWrapper（带"复制"按钮）
 *
 * updateInPlace / renderForStreaming 仍保持单 JTextPane 路径，不引入代码块拆分，
 * 以确保流式渲染不出现闪烁或布局震荡。
 */
class MarkdownRenderer {

    private val flavour = org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor()

    // ────────────────────────────────────────────────────────────────────────
    // 分段逻辑：把 markdown 拆为 TextSegment / CodeSegment 交替序列
    // ────────────────────────────────────────────────────────────────────────

    private sealed class Segment
    private data class TextSegment(val text: String) : Segment()
    private data class CodeSegment(val language: String, val code: String) : Segment()

    /**
     * 用正则把 markdown 按围栏代码块分割为文本段与代码段交替序列。
     * 仅处理标准 ``` 围栏，不处理缩进代码块（保留在文本段中由 HTML 渲染）。
     */
    private fun splitSegments(markdown: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        // 匹配 ```lang\n...code...\n``` 围栏块（支持空语言标识）
        val fencePattern = Regex("""```([^\n`]*)\n([\s\S]*?)```""")
        var lastEnd = 0
        for (match in fencePattern.findAll(markdown)) {
            val beforeText = markdown.substring(lastEnd, match.range.first)
            if (beforeText.isNotEmpty()) {
                segments += TextSegment(beforeText)
            }
            val lang = match.groupValues[1].trim()
            val code = match.groupValues[2]
            // 去掉末尾多余换行，但保留内部结构
            segments += CodeSegment(lang, code.trimEnd('\n'))
            lastEnd = match.range.last + 1
        }
        val tail = markdown.substring(lastEnd)
        if (tail.isNotEmpty()) {
            segments += TextSegment(tail)
        }
        return segments
    }

    // ────────────────────────────────────────────────────────────────────────
    // 公开 API
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 将 markdown 渲染为 JPanel（Y 轴 BoxLayout）。
     * - 文本段：JTextPane with HTML
     * - 代码段：CodeBlockWrapper（带语言标签 + 右上角"复制"按钮）
     */
    fun render(markdown: String): JPanel = render(markdown, null, null)

    /**
     * 将 markdown 渲染为 JPanel，支持文件路径/URL 点击跳转。
     * @param project 当前项目，非 null 时启用文件路径点击跳转
     * @param basePath 项目根路径，默认使用 project.basePath
     */
    fun render(markdown: String, project: com.intellij.openapi.project.Project?, basePath: String? = null): JPanel {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)

        val segments = splitSegments(markdown)

        // 如果没有任何代码段，走单 textPane 路径（向后兼容，也利于 fitWidth 测量）
        if (segments.none { it is CodeSegment }) {
            val html = markdownToHtml(markdown)
            val textPane = buildTextPane(container.background)
            textPane.text = buildStyledHtml(html, textPane)
            textPane.caretPosition = 0
            if (project != null) com.aiassistant.ui.FilePathNavigator.attach(textPane, project, basePath)
            container.add(textPane)
            return container
        }

        // 有代码段：逐段渲染
        for (seg in segments) {
            when (seg) {
                is TextSegment -> {
                    val html = markdownToHtml(seg.text)
                    val textPane = buildTextPane(container.background)
                    textPane.text = buildStyledHtml(html, textPane)
                    textPane.caretPosition = 0
                    if (project != null) com.aiassistant.ui.FilePathNavigator.attach(textPane, project, basePath)
                    container.add(textPane)
                }
                is CodeSegment -> {
                    val wrapper = CodeBlockWrapper(seg.language, seg.code)
                    container.add(wrapper)
                }
            }
        }

        return container
    }

    /**
     * 原地更新已渲染的 panel 内容，避免 remove/add 触发布局震荡。
     * 流式渲染专用：始终假设 components[0] 是 JTextPane（单 pane 路径）。
     * @return true 如果高度变化超过阈值（调用方需要 revalidate）
     */
    fun updateInPlace(container: JPanel, markdown: String): Boolean {
        if (container.componentCount == 0) return false
        val textPane = container.components[0] as? JTextPane ?: return false
        val oldHeight = container.preferredSize?.height ?: 0

        val html = markdownToHtml(markdown)
        textPane.text = buildStyledHtml(html, textPane)
        textPane.caretPosition = textPane.document.length

        // 强制重新布局后测量，确保换行高度正确
        val w = container.width
        if (w > 10) {
            container.setSize(w, Short.MAX_VALUE.toInt())
            container.doLayout()
        }
        val newHeight = container.preferredSize?.height ?: 0
        return kotlin.math.abs(newHeight - oldHeight) > 10
    }

    fun renderForStreaming(markdown: String, parentBackground: java.awt.Color): JPanel {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.background = parentBackground

        val html = markdownToHtml(markdown)

        val textPane = buildTextPane(parentBackground)
        textPane.text = buildStyledHtml(html, textPane)
        textPane.caretPosition = 0
        container.add(textPane)

        return container
    }

    /**
     * Creates a syntax-highlighted code panel using IntelliJ EditorTextField.
     * This is used when the markdown contains code blocks with a language specifier.
     */
    fun createCodeBlockPanel(language: String, code: String): JPanel {
        val panel = JPanel(BorderLayout())

        try {
            panel.border = JBUI.Borders.customLine(JBColor.border(), 1)

            val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                background = ChatTheme.codeHeaderBg
                border = JBUI.Borders.empty(4, 8)
            }
            val langLabel = JLabel(language.uppercase()).apply {
                font = font.deriveFont(Font.BOLD, ChatTheme.CODE_LANG_FONT_SIZE.toFloat())
                foreground = ChatTheme.codeLangFg
            }
            val copyButton = JButton("Copy").apply {
                font = font.deriveFont(Font.PLAIN, ChatTheme.CODE_LANG_FONT_SIZE.toFloat())
                addActionListener {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(code), null)
                }
            }
            headerPanel.add(langLabel)
            headerPanel.add(copyButton)

            val editorField = EditorTextField().apply {
                setOneLineMode(false)
                isViewer = true
                background = ChatTheme.codeEditorBg
            }
            editorField.document.setText(code)

            panel.add(headerPanel, BorderLayout.NORTH)
            panel.add(editorField, BorderLayout.CENTER)
        } catch (e: Exception) {
            // Fallback when IntelliJ platform is not available (e.g., tests)
            panel.add(JLabel("<html><pre>${code.replace("<", "&lt;")}</pre></html>"))
        }

        return panel
    }

    // ────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ────────────────────────────────────────────────────────────────────────

    private fun markdownToHtml(markdown: String): String {
        return try {
            val parsedTree = org.intellij.markdown.parser.MarkdownParser(flavour)
                .buildMarkdownTreeFromString(markdown)
            org.intellij.markdown.html.HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
        } catch (e: Exception) {
            markdown.replace("\n", "<br>")
        }
    }

    private fun buildTextPane(bg: Color?): JTextPane = JTextPane().apply {
        isEditable = false
        contentType = "text/html"
        editorKit = HTMLEditorKit()
        border = JBUI.Borders.empty()
        isOpaque = false  // 不画背景，由 ContentPanel 统一提供 aiBg
    }

    private fun buildStyledHtml(htmlBody: String, textPane: JTextPane): String {
        val scheme = try {
            EditorColorsManager.getInstance().globalScheme
        } catch (e: Exception) {
            null
        }
        val fontFamily = scheme?.editorFontName ?: "Segoe UI"
        val fontSize = scheme?.editorFontSize ?: 14
        val bg = textPane.background
        val fg = textPane.foreground
        val bgHex = String.format("%02x%02x%02x", bg.red, bg.green, bg.blue)
        val fgHex = String.format("%02x%02x%02x", fg.red, fg.green, fg.blue)

        // Border color depends on theme brightness
        val isDark = (bg.red + bg.green + bg.blue) / 3 < 128
        val borderColor = if (isDark) "#3C3C3C" else "#DCDCDC"
        val inlineBg = if (isDark) "#3C3C3C" else "#F0F0F0"
        val mutedFg = if (isDark) "#8C8C8C" else "#666666"

        // Swing HTMLEditorKit 仅支持 CSS 1.0 子集: 无 border-radius/overflow/单位less line-height/引号字体名
        return """
            <html><head><style>
                body {
                    font-family: $fontFamily, sans-serif;
                    font-size: ${fontSize - 1}px;
                    color: #$fgHex;
                    background: transparent;
                    padding: 4px;
                    margin: 0;
                }
                pre {
                    background-color: $inlineBg;
                    border: 1px solid $borderColor;
                    padding: 10px 12px;
                    font-family: $fontFamily, monospace;
                    font-size: ${fontSize - 1}px;
                }
                code {
                    font-family: $fontFamily, monospace;
                    background-color: $inlineBg;
                    padding: 1px 4px;
                    font-size: ${fontSize - 1}px;
                }
                p { margin: 4px 0; }
                ul, ol { margin: 4px 0; }
                li { margin: 2px 0; }
                h1 { font-size: ${fontSize + ChatTheme.HEADING_FONT_OFFSET_H1}px; margin: 12px 0 4px 0; }
                h2 { font-size: ${fontSize + ChatTheme.HEADING_FONT_OFFSET_H2}px; margin: 12px 0 4px 0; }
                h3 { font-size: ${fontSize + ChatTheme.HEADING_FONT_OFFSET_H3}px; margin: 12px 0 4px 0; }
                h4 { margin: 12px 0 4px 0; }
                blockquote {
                    border-left: 3px solid $borderColor;
                    margin: 8px 0;
                    padding: 2px 12px;
                    color: $mutedFg;
                }
                table { width: 100%; }
                th, td { border: 1px solid $borderColor; padding: 6px 10px; }
                th { background-color: $inlineBg; }
                a { color: #2674B4; text-decoration: underline; }
                hr { border: none; border-top: 1px solid $borderColor; }
            </style></head><body>$htmlBody</body></html>
        """.trimIndent()
    }

    // ────────────────────────────────────────────────────────────────────────
    // 代码块包装组件：语言标签 + 右上角"复制"按钮
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 代码块容器：
     *   NORTH  — 标题栏（语言标签居左，复制按钮居右，hover 可见）
     *   CENTER — 代码文本（JTextArea，等宽字体，不可编辑）
     *
     * 使用 ChatTheme token，兼容亮/暗主题。
     * 复制按钮默认低调透明，hover 时显示为辅助色文字；点击后短暂显示"已复制"。
     */
    private inner class CodeBlockWrapper(language: String, private val code: String) : JPanel(BorderLayout()) {

        init {
            isOpaque = true
            background = ChatTheme.codeBg
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(ChatTheme.codeBorder, 1),
                JBUI.Borders.empty(0, 0, 4, 0)
            )

            // ── 标题栏 ──────────────────────────────────────────────
            val titleBar = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = ChatTheme.codeBg
                border = JBUI.Borders.empty(4, 10, 4, 8)
            }

            val langLabel = JLabel(if (language.isNotEmpty()) language.uppercase() else "CODE").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                foreground = ChatTheme.textMuted
            }

            // 复制按钮：平时低调，hover 显色
            val copyBtn = buildCopyButton()

            titleBar.add(langLabel, BorderLayout.WEST)
            titleBar.add(copyBtn, BorderLayout.EAST)

            // ── 代码区域 ─────────────────────────────────────────────
            val codeArea = buildCodeArea()

            add(titleBar, BorderLayout.NORTH)
            add(codeArea, BorderLayout.CENTER)
        }

        private fun buildCopyButton(): JLabel {
            val btn = JLabel("复制").apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
                foreground = ChatTheme.textSecondary
                // 默认透明度低 — 通过颜色而非 alpha（Swing alpha paint 有坑）
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 6)
            }

            btn.addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    btn.foreground = ChatTheme.textPrimary
                }
                override fun mouseExited(e: MouseEvent) {
                    if (btn.text == "复制") btn.foreground = ChatTheme.textSecondary
                }
                override fun mouseClicked(e: MouseEvent) {
                    copyToClipboard()
                    showCopiedFeedback(btn)
                }
            })

            return btn
        }

        private fun buildCodeArea(): JScrollPane {
            val area = JTextArea(code).apply {
                isEditable = false
                lineWrap = false
                wrapStyleWord = false
                font = ChatTheme.codeFont
                background = ChatTheme.codeBg
                foreground = ChatTheme.textPrimary
                border = JBUI.Borders.empty(6, 10)
                // 禁用焦点边框
                isFocusable = false
            }
            // 水平滚动条按需出现，垂直不显示（让父容器决定高度）
            return JScrollPane(area).apply {
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                border = null
                isOpaque = false
                viewport.isOpaque = false
                background = ChatTheme.codeBg
                viewport.background = ChatTheme.codeBg
            }
        }

        private fun copyToClipboard() {
            try {
                // 优先用 IntelliJ CopyPasteManager（支持 IDE 内部历史记录）
                val cpm = Class.forName("com.intellij.openapi.ide.CopyPasteManager")
                val getInstance = cpm.getMethod("getInstance")
                val instance = getInstance.invoke(null)
                val setContents = cpm.getMethod("setContents", java.awt.datatransfer.Transferable::class.java)
                setContents.invoke(instance, StringSelection(code))
            } catch (_: Exception) {
                // 降级到系统剪贴板
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            }
        }

        private fun showCopiedFeedback(btn: JLabel) {
            btn.text = "已复制"
            btn.foreground = ChatTheme.doneCheck
            // 1.5 秒后恢复。用 javax.swing.Timer（单 EDT 线程、回调在 EDT），
            // 而非 java.util.Timer —— 后者每次 new 都会泄漏一个不回收的后台线程。
            val swingTimer = javax.swing.Timer(1500) {
                btn.text = "复制"
                btn.foreground = ChatTheme.textSecondary
            }
            swingTimer.isRepeats = false
            swingTimer.start()
        }
    }
}
