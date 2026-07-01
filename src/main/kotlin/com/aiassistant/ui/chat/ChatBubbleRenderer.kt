package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext
import javax.swing.text.StyledDocument

// 聊天气泡渲染 — 支持 Markdown + 亮/暗主题

object ChatBubbleRenderer {

    // ponytail: JetBrains Mono → Monospaced fallback
    private val monoFont = run {
        val jetbrains = Font("JetBrains Mono", Font.PLAIN, 12)
        if ("JetBrains Mono" in java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames) {
            jetbrains
        } else {
            Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
    }

    fun render(
        msg: ChatMessage,
        onRetry: (() -> Unit)? = null,
        panelWidth: Int = 0
    ): JComponent {
        return when (msg.type) {
            ChatMessage.Type.USER_TEXT -> renderUserBubble(msg)
            ChatMessage.Type.AGENT_TEXT -> renderAgentBubble(msg, panelWidth)
            ChatMessage.Type.ERROR -> renderErrorBubble(msg, onRetry)
            ChatMessage.Type.SYSTEM -> renderSystemMsg(msg)
            ChatMessage.Type.TOOL_CALL -> renderToolCallPlaceholder(msg)
        }
    }

    private fun renderUserBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            putClientProperty("bubbleType", "user")
            isOpaque = false
        }
        // ponytail: FlowLayout 右对齐，高度仅由内容决定，避免 BorderLayout.EAST 纵向拉伸
        val textRow = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
        }
        val text = JLabel(
            "<html><body style='width:100%;max-width:480px;margin:0;padding:0'>${
                escapeHtml(msg.content).replace("\n", "<br>")
            }</body></html>"
        ).apply {
            isOpaque = true; background = AppColors.userBubbleBg; font = font.deriveFont(14f)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        textRow.add(text)
        wrapper.add(textRow)
        wrapper.add(renderTimestamp(msg))
        return wrapper
    }

    private fun renderAgentBubble(
        msg: ChatMessage,
        panelWidth: Int = 0
    ): JPanel {
        // ponytail: BoxLayout.Y_AXIS 避免 BorderLayout.CENTER 纵向拉伸
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            putClientProperty("bubbleType", "agent")
            isOpaque = true
            background = AppColors.cardBg
        }
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

        val blocks = parseMarkdown(msg.content)
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    val rendered = block.text
                        .replace(Regex("`([^`]+)`")) {
                            val codeBgHex = AppColors.inlineCodeBg.toHtmlColor()
                            val codeBorderHex = AppColors.inlineCodeBorder.toHtmlColor()
                            "<code style='font-family:JetBrains Mono,monospace;font-size:13px;background:${codeBgHex};padding:1px 6px;border-radius:3px;border:1px solid ${codeBorderHex}'>${it.groupValues[1]}</code>"
                        }
                    body.add(
                        JLabel(
                            "<html><body style='width:100%;max-width:520px;margin:0;padding:0'>${
                                escapeHtml(
                                    rendered
                                ).replace("\n", "<br>")
                            }</body></html>"
                        ).apply {
                            font = font.deriveFont(12f)
                            border = BorderFactory.createEmptyBorder(1, 0, 1, 0)
                        })
                    i++
                }

                is MarkdownBlock.CodeBlock -> {
                    // 收集连续的代码块，支持并排显示（对齐 docs/ui/design-system.md §八）
                    val consecutiveCodes = mutableListOf<MarkdownBlock.CodeBlock>()
                    while (i < blocks.size && blocks[i] is MarkdownBlock.CodeBlock) {
                        consecutiveCodes.add(blocks[i] as MarkdownBlock.CodeBlock)
                        i++
                    }
                    if (consecutiveCodes.size >= 2 && panelWidth > 500) {
                        // 多个连续短代码块：面板宽度 > 500px 时并排显示（对齐 docs/ui/design-system.md §八）
                        val row = JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.X_AXIS)
                            isOpaque = false
                            alignmentX = java.awt.Component.LEFT_ALIGNMENT
                        }
                        consecutiveCodes.forEachIndexed { idx, cb ->
                            val code = createHighlightedCodePane(cb.code).apply {
                                border = BorderFactory.createCompoundBorder(
                                    RoundedBorder(8, AppColors.codeBorder),
                                    BorderFactory.createEmptyBorder(6, 8, 6, 8)
                                )
                            }
                            val scrollPane = JScrollPane(code).apply {
                                horizontalScrollBarPolicy =
                                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                                border = BorderFactory.createEmptyBorder()
                                minimumSize = java.awt.Dimension(80, minimumSize.height)
                            }
                            row.add(scrollPane)
                            if (idx < consecutiveCodes.size - 1) {
                                row.add(Box.createHorizontalStrut(8))
                            }
                        }
                        body.add(row)
                    } else if (consecutiveCodes.size >= 2) {
                        // 面板宽度 <= 500px：连续代码块垂直堆叠显示
                        consecutiveCodes.forEach { cb ->
                            val code = JTextArea(cb.code).apply {
                                font = monoFont
                                background = AppColors.codeBg; foreground = AppColors.textSecondary
                                border = BorderFactory.createCompoundBorder(
                                    RoundedBorder(8, AppColors.codeBorder),
                                    BorderFactory.createEmptyBorder(6, 8, 6, 8)
                                )
                                isEditable = false; lineWrap = false
                            }
                            val scrollPane = JScrollPane(code).apply {
                                horizontalScrollBarPolicy =
                                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                                border = BorderFactory.createEmptyBorder()
                            }
                            body.add(scrollPane)
                            body.add(Box.createVerticalStrut(8))
                        }
                    } else {
                        val cb = consecutiveCodes.first()
                        val code = createHighlightedCodePane(cb.code).apply {
                            border = BorderFactory.createCompoundBorder(
                                RoundedBorder(8, AppColors.codeBorder),
                                BorderFactory.createEmptyBorder(8, 10, 8, 10)
                            )
                        }
                        val scrollPane = JScrollPane(code).apply {
                            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                            border = BorderFactory.createEmptyBorder()
                        }
                        body.add(scrollPane)
                    }
                }

                is MarkdownBlock.Header -> {
                    body.add(JLabel("<html><b style='font-size:14px'>${escapeHtml(block.text)}</b></html>"))
                    i++
                }

                is MarkdownBlock.ListItem -> {
                    body.add(JLabel("<html>&nbsp;&nbsp;• ${escapeHtml(block.text)}</html>"))
                    i++
                }

                is MarkdownBlock.QuoteBlock -> {
                    body.add(JTextArea(block.text).apply {
                        font = Font(Font.SANS_SERIF, Font.ITALIC, 12); foreground =
                        AppColors.textSecondary
                        background = AppColors.quoteBg; isEditable = false; lineWrap = true
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.quoteBorder),
                            BorderFactory.createEmptyBorder(4, 8, 4, 8)
                        )
                    })
                    i++
                }
            }
        }

        wrapper.isOpaque = false
        wrapper.accessibleContext.accessibleDescription = "Agent 消息: ${msg.content.take(100)}"
        wrapper.add(body)

        // 底部行：时间戳+token信息
        val bottomRow = JPanel(BorderLayout()).apply { isOpaque = false }
        val tokenInfo =
            if (msg.tokenDelta != null) "↑${msg.tokenDelta.input / 1000}K ↓${msg.tokenDelta.output / 1000}K" else ""
        bottomRow.add(renderTimestamp(msg, tokenInfo), BorderLayout.WEST)

        wrapper.add(bottomRow)
        // 对齐 docs/ui/components.md：padding=12px + left accent bar 3px
        wrapper.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(12, 12, 12, 12),
            BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary)
        )
        wrapper.isOpaque = true
        wrapper.background = AppColors.cardBg
        return wrapper
    }

    private fun renderErrorBubble(msg: ChatMessage, onRetry: (() -> Unit)?): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "error")
            accessibleContext.accessibleDescription = "错误消息: ${msg.content.take(100)}"
            isOpaque = false
        }
        wrapper.add(
            JLabel(
                "<html><body style='width:100%;max-width:480px;margin:0;padding:0'>❌ ${
                    escapeHtml(
                        msg.content
                    )
                }</body></html>"
            ).apply {
                isOpaque = true
                background = AppColors.errorBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 10, 6, 10),
                BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.error)
            )
        }, BorderLayout.CENTER)
        wrapper.add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            onRetry?.let {
                add(JButton("🔄 重试").apply {
                    accessibleContext.accessibleDescription = "重新发送失败的消息"
                    addActionListener { it() }
                })
            }
            add(JButton("📋 复制").apply {
                accessibleContext.accessibleDescription = "复制错误消息到剪贴板"
                addActionListener {
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            StringSelection(msg.content),
                            null
                        )
                    }
                }
            })
        }, BorderLayout.SOUTH)
        return wrapper
    }

    private fun renderSystemMsg(msg: ChatMessage): JPanel {
        val p = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "system")
        }
        p.add(JLabel(msg.content, SwingConstants.CENTER).apply {
            foreground = AppColors.textTertiary; font = font.deriveFont(11f)
        }, BorderLayout.CENTER)
        return p
    }

    private fun renderToolCallPlaceholder(msg: ChatMessage): JPanel {
        val toolCall = msg.toolCall
        val state = runCatching {
            ToolCallCard.ToolCallState.valueOf(toolCall?.state ?: "PENDING")
        }.getOrDefault(ToolCallCard.ToolCallState.PENDING)
        return ToolCallCard(
            toolName = toolCall?.toolName ?: msg.content.ifBlank { "tool" },
            params = msg.content,
            initialState = state
        ).apply {
            setState(state, toolCall?.result, toolCall?.durationMs)
            // Edit 工具执行成功后内联展示可视化 Diff（对齐 docs/ui/chat.md §四 "Diff 可视化"）
            if (toolCall?.toolName == "Edit" && state == ToolCallCard.ToolCallState.DONE) {
                val params = toolCall.parameters
                val oldString = params["oldString"] as? String
                val newString = params["newString"] as? String
                if (oldString != null && newString != null) {
                    renderDiff(oldString, newString)
                }
            }
        }
    }

    private fun renderTimestamp(msg: ChatMessage, extra: String = ""): JPanel {
        val ts = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(msg.timestamp)
        val label = if (extra.isNotEmpty()) "$ts | $extra" else ts
        return JPanel(BorderLayout()).apply {
            add(JLabel(label).apply {
                foreground = AppColors.textSecondary; font = font.deriveFont(10f)
            }, BorderLayout.EAST)
        }
    }

    /**
     * 流式 Markdown 渲染 — 字符串缓冲累积，Block 闭合后通过 parseMarkdown() 渲染为组件。
     * 未闭合 Markdown 块（如未配对的 ```）缓存等待，闭合后再渲染。
     *
     * 对齐 docs/ui/chat.md §二 "流式气泡"：末尾闪烁光标 ▍ (#3B82F6, 500ms blink)。
     */
    fun renderStreaming(markdownText: String): JComponent {
        val bubble = render(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = markdownText,
                timestamp = java.time.Instant.now()
            )
        )
        // ponytail: 光标▍追加到气泡末尾
        val cursor = JLabel("▍").apply {
            foreground = AppColors.primary
            font = font.deriveFont(13f)
            isOpaque = false
        }
        if (bubble is JPanel) {
            // 将光标添加到 CENTER 区域的最后一行（body panel 末尾）
            val body =
                bubble.components.firstOrNull { it is JPanel && it.layout is BoxLayout } as? JPanel
            body?.add(cursor)
        }
        val blinkTimer = javax.swing.Timer(500) { cursor.isVisible = !cursor.isVisible }
        blinkTimer.start()
        bubble.addHierarchyListener {
            if (it.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
                if (!bubble.isDisplayable) blinkTimer.stop()
            }
        }
        return bubble
    }

    /**
     * 渲染思考过程折叠块（对齐 docs/ui/chat.md §三）。
     * 默认折叠，">" 箭头可点击展开/折叠，显示完整思考内容。
     */
    fun renderThinking(reasoning: String, durationMs: Long): JPanel {
        val block = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.thinkingBg
            // 对齐 ui-prototype.html .thinking-block: border-radius=8px
            border = RoundedBorder(8, AppColors.thinkingBorder)
        }
        val arrowLabel = JLabel("▶").apply {
            // 对齐 ui-prototype.html .thinking-header: color=#B45309 (amber-700)
            foreground = AppColors.thinkingTimeFg
            font = font.deriveFont(12f)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 4)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            // 对齐 ui-prototype: padding=6px 10px
            background = AppColors.thinkingBg; border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val leftPanel = JPanel().apply {
                isOpaque = true; background = AppColors.thinkingBg
                add(arrowLabel)
                add(JLabel("💭 思考过程").apply {
                    foreground = AppColors.thinkingTimeFg
                })
            }
            add(leftPanel, BorderLayout.WEST)
            add(JLabel("${durationMs / 1000}.${(durationMs % 1000) / 100}s").apply {
                foreground = AppColors.thinkingTimeFg; font = font.deriveFont(10f)
            }, BorderLayout.EAST)
        }
        val body = JTextArea(reasoning).apply {
            font = Font(Font.SANS_SERIF, Font.ITALIC, 11); foreground = AppColors.thinkingBodyFg
            background = AppColors.thinkingBg; isEditable = false; lineWrap = true
            // 对齐 ui-prototype: body 展开时 border-top=1px solid amber-100
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppColors.thinkingBorder),
                BorderFactory.createEmptyBorder(6, 10, 10, 10)
            )
            isVisible = false
        }
        // ponytail: 默认折叠，bodyScroll 初始不可见，避免 BorderLayout.CENTER 占位 160px
        val bodyScroll = JScrollPane(body).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder()
            isVisible = false
            maximumSize = Dimension(Int.MAX_VALUE, 160)
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val expand = !body.isVisible
                body.isVisible = expand
                bodyScroll.isVisible = expand
                arrowLabel.text = if (expand) "▾" else "▶"
                block.revalidate()
                block.repaint()
            }
        })
        block.add(header, BorderLayout.NORTH); block.add(bodyScroll, BorderLayout.CENTER)
        return block
    }

    /**
     * 创建带语法高亮的代码面板（对齐 ui-prototype.html .code-block 四色语法高亮）。
     * 使用 JTextPane + StyledDocument 实现 Kotlin 关键字/字符串/注释/函数名着色。
     */
    private fun createHighlightedCodePane(code: String): JTextPane {
        val pane = JTextPane().apply {
            font = monoFont
            background = AppColors.codeBg
            isEditable = false
        }
        val doc = pane.styledDocument
        val def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        val defaultStyle = doc.addStyle("code", def).apply {
            StyleConstants.setForeground(this, AppColors.textSecondary)
        }
        val kwStyle = doc.addStyle("kw", defaultStyle).apply {
            StyleConstants.setForeground(this, Color(0xCF222E))
            StyleConstants.setBold(this, true)
        }
        val strStyle = doc.addStyle("str", defaultStyle).apply {
            StyleConstants.setForeground(this, Color(0x0A3069))
        }
        val cmStyle = doc.addStyle("cm", defaultStyle).apply {
            StyleConstants.setForeground(this, Color(0x6E7781))
            StyleConstants.setItalic(this, true)
        }
        val fnStyle = doc.addStyle("fn", defaultStyle).apply {
            StyleConstants.setForeground(this, Color(0x8250DF))
        }

        val kotlinKw = setOf(
            "fun", "val", "var", "class", "object", "interface", "data", "sealed", "abstract",
            "open", "override", "private", "protected", "internal", "public", "suspend", "inline",
            "operator", "infix", "tailrec", "return", "if", "else", "when", "for", "while", "do",
            "try", "catch", "finally", "throw", "import", "package", "typealias", "companion",
            "const", "lateinit", "this", "super", "null", "true", "false", "is", "as", "in", "out",
            "where", "by", "get", "set", "constructor", "init", "annotation", "enum"
        )
        try {
            doc.insertString(0, code, defaultStyle)
            Regex("\\b(${kotlinKw.joinToString("|")})\\b").findAll(code).forEach { m ->
                doc.setCharacterAttributes(m.range.first, m.value.length, kwStyle, false)
            }
            Regex("//[^\n]*").findAll(code).forEach { m ->
                doc.setCharacterAttributes(m.range.first, m.value.length, cmStyle, false)
            }
            Regex("\"[^\"]*\"").findAll(code).forEach { m ->
                doc.setCharacterAttributes(m.range.first, m.value.length, strStyle, false)
            }
            Regex("\\b([a-z][a-zA-Z0-9]*)\\s*\\(").findAll(code).forEach { m ->
                doc.setCharacterAttributes(m.range.first, m.groupValues[1].length, fnStyle, false)
            }
        } catch (_: Exception) { /* fallback to default style */
        }
        return pane
    }

    private fun parseMarkdown(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        if (buf.isNotEmpty()) buf.append("\n")
                        buf.append(lines[i]); i++
                    }
                    i++
                    blocks.add(MarkdownBlock.CodeBlock(buf.toString()))
                }

                line.startsWith("#") -> {
                    blocks.add(MarkdownBlock.Header(line.removePrefix("#").trim())); i++
                }

                line.startsWith("> ") -> {
                    val buf = StringBuilder(line.removePrefix("> ").trim())
                    i++; while (i < lines.size && lines[i].startsWith("> ")) {
                        buf.append("\n").append(lines[i].removePrefix("> ").trim()); i++
                    }
                    blocks.add(MarkdownBlock.QuoteBlock(buf.toString()))
                }

                line.matches(Regex("""^\d+[.)]\s.*""")) -> {
                    blocks.add(
                        MarkdownBlock.ListItem(
                            line.substring(line.indexOf(' ') + 1).trim()
                        )
                    ); i++
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    blocks.add(MarkdownBlock.ListItem(line.substring(2).trim())); i++
                }

                line.startsWith("---") || line.startsWith("──") -> {
                    i++
                }

                line.isBlank() -> {
                    i++
                }

                else -> {
                    val buf = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank()
                        && !lines[i].startsWith("```") && !lines[i].startsWith("#")
                        && !lines[i].startsWith("- ") && !lines[i].startsWith("* ")
                        && !lines[i].startsWith("---") && !lines[i].startsWith("──")
                    ) {
                        if (buf.isNotEmpty()) buf.append("\n")
                        buf.append(lines[i]); i++
                    }
                    blocks.add(MarkdownBlock.Paragraph(buf.toString()))
                }
            }
        }
        return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(text)) }
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
    data class Header(val text: String) : MarkdownBlock()
    data class ListItem(val text: String) : MarkdownBlock()
    data class QuoteBlock(val text: String) : MarkdownBlock()
}
