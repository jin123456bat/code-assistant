package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.*
import javax.swing.text.StyleConstants
import javax.swing.text.StyleContext
import javax.swing.text.StyledDocument

// 聊天气泡渲染 — 支持 Markdown + 亮/暗主题

object ChatBubbleRenderer {

    // ponytail: JetBrains Mono → Monospaced fallback
    private val monoFont = run {
        val jetbrains = Font("JetBrains Mono", Font.PLAIN, 13)
        if ("JetBrains Mono" in java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames) {
            jetbrains
        } else {
            Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
    }

    fun render(
        msg: ChatMessage,
        onRetry: (() -> Unit)? = null,
        panelWidth: Int = 0
    ): JComponent {
        return when (msg.type) {
            ChatMessage.Type.USER_TEXT -> renderUserBubble(msg, panelWidth)
            ChatMessage.Type.AGENT_TEXT -> renderAgentBubble(msg, panelWidth)
            ChatMessage.Type.ERROR -> renderErrorBubble(msg, onRetry)
            ChatMessage.Type.SYSTEM -> renderSystemMsg(msg)
            ChatMessage.Type.TOOL_CALL -> renderToolCallPlaceholder(msg)
        }
    }

    private fun renderUserBubble(msg: ChatMessage, panelWidth: Int = 0): JPanel {
        // 外层 FlowLayout.RIGHT 强制右对齐，不依赖 BoxLayout alignmentX
        val outer = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            putClientProperty("bubbleType", "user")
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        // 圆角气泡容器 — 自定义 painting 实现圆角填充背景
        val bubble = JPanel().apply {
            isOpaque = false
        }
        bubble.layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        val text = wrappingLabel(
            escapeHtml(msg.content).replace("\n", "<br>"),
            0
        ).apply {
            isOpaque = false; font = font.deriveFont(14f)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
        bubble.add(text)
        bubble.setUI(object : javax.swing.plaf.PanelUI() {
            override fun paint(g: Graphics, c: JComponent) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
                g2.color = AppColors.userBubbleBg
                g2.fillRoundRect(0, 0, c.width - 1, c.height - 1, 24, 24)
                g2.dispose()
            }
        })
        content.add(bubble)
        content.add(renderTimestamp(msg))
        outer.add(content)
        return capRowHeight(outer)
    }

    private fun renderAgentBubble(
        msg: ChatMessage,
        panelWidth: Int = 0
    ): JPanel {
        // 外层 FlowLayout.LEFT 强制左对齐，不依赖 BoxLayout alignmentX
        val outer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            putClientProperty("bubbleType", "agent")
        }
        // ponytail: BoxLayout.Y_AXIS 避免 BorderLayout.CENTER 纵向拉伸
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = AppColors.cardBg
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }

        val blocks = parseMarkdown(msg.content)
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    val rendered = escapeHtml(block.text)
                        .replace(Regex("`([^`]+)`")) {
                            "<code>${it.groupValues[1]}</code>"
                        }
                    body.add(
                        leftAlignedRow(
                        wrappingLabel(
                            rendered.replace("\n", "<br>"),
                            0
                        ).apply {
                            font = font.deriveFont(12f)
                            border = BorderFactory.createEmptyBorder(1, 0, 1, 0)
                        }
                    ))
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
                    body.add(
                        leftAlignedRow(
                            wrappingLabel(
                                "<b style='font-size:14px'>${escapeHtml(block.text)}</b>",
                                0
                            )
                        )
                    )
                    i++
                }

                is MarkdownBlock.ListItem -> {
                    body.add(
                        leftAlignedRow(
                            wrappingLabel("&nbsp;&nbsp;• ${escapeHtml(block.text)}", 0)
                        )
                    )
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
        // 对齐 docs/ui/components.md：padding=12px + left accent bar 3px（accent bar 紧贴左边缘）
        wrapper.border = BorderFactory.createCompoundBorder(
            RoundedBorder(12, AppColors.border),
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
            )
        )
        wrapper.isOpaque = true
        wrapper.background = AppColors.cardBg
        outer.add(wrapper)
        return capRowHeight(outer)
    }

    fun updateWrappingLabels(container: Component, width: Int) {
        if (container is JLabel) {
            val html = container.getClientProperty("wrapHtml") as? String
            if (html != null) {
                container.text = htmlWithWidth(html, width)
                container.maximumSize =
                    Dimension(container.preferredSize.width, container.preferredSize.height)
            }
        }
        if (container is java.awt.Container) {
            container.components.forEach { updateWrappingLabels(it, width) }
        }
        refreshCappedHeight(container)
    }

    fun refreshCappedHeights(container: Component) {
        if (container is java.awt.Container) {
            container.components.forEach { refreshCappedHeights(it) }
        }
        refreshCappedHeight(container)
    }

    private fun wrappingLabel(html: String, width: Int): JLabel =
        JLabel(htmlWithWidth(html, width)).apply {
            putClientProperty("wrapHtml", html)
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.TOP
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun leftAlignedRow(component: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            putClientProperty("capHeight", true)
            add(component)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun htmlWithWidth(html: String, width: Int): String =
        if (width > 0) {
            "<html><body width='${width.coerceAtLeast(80)}'>$html</body></html>"
        } else {
            "<html>$html</html>"
        }

    private fun renderErrorBubble(msg: ChatMessage, onRetry: (() -> Unit)?): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "error")
            accessibleContext.accessibleDescription = "错误消息: ${msg.content.take(100)}"
            isOpaque = false
        }
        wrapper.add(
            JLabel(
                "<html>❌ ${
                    escapeHtml(
                        msg.content
                    )
                }</html>"
            ).apply {
                isOpaque = true
                background = AppColors.errorBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.error),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
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
     * 流式 Markdown 渲染。
     *
     * 对齐 docs/ui/chat.md §二 "流式气泡"：末尾闪烁光标 ▍ (#3B82F6, 500ms blink)。
     */
    fun renderStreaming(markdownText: String): JComponent = buildStreamingPanel(markdownText)

    /** 构建带闪烁光标的流式气泡面板（首次调用时创建） */
    private fun buildStreamingPanel(markdownText: String): JPanel {
        val bubble = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            putClientProperty("bubbleType", "agent")
        }
        val rendered = render(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = markdownText,
                timestamp = java.time.Instant.now()
            )
        )
        bubble.add(rendered)
        val cursor = JLabel("▍").apply {
            foreground = AppColors.primary
            font = font.deriveFont(13f)
            isOpaque = false
        }
        bubble.add(cursor)
        val blinkTimer = javax.swing.Timer(500) { cursor.isVisible = !cursor.isVisible }
        blinkTimer.start()
        bubble.addHierarchyListener {
            if (it.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
                if (!bubble.isDisplayable) blinkTimer.stop()
            }
        }
        return capRowHeight(bubble)
    }

    /**
     * 渲染思考过程折叠块（对齐 docs/ui/chat.md §三）。
     * 默认折叠，">" 箭头可点击展开/折叠，显示完整思考内容。
     */
    fun renderThinking(reasoning: String, durationMs: Long): JPanel {
        val outer = JPanel(BorderLayout()).apply {
            isOpaque = false
            putClientProperty("bubbleType", "agent")  // 标记为 agent 类型，参与宽度约束
            putClientProperty("fullWidth", true)
        }
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
            wrapStyleWord = true
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
            preferredSize = Dimension(0, minOf(body.preferredSize.height, 140))
            maximumSize = Dimension(Int.MAX_VALUE, 140)
            isVisible = false
        }
        val toggleThinking = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val expand = !body.isVisible
                body.isVisible = expand
                bodyScroll.isVisible = expand
                arrowLabel.text = if (expand) "▾" else "▶"
                val currentWidth = listOf(outer.width, block.width, outer.preferredSize.width)
                    .firstOrNull { it > 0 } ?: 0
                block.preferredSize = null
                outer.preferredSize = null
                if (currentWidth > 0) {
                    updateThinkingBodySize(body, bodyScroll, currentWidth)
                    block.preferredSize = Dimension(currentWidth, block.preferredSize.height)
                }
                outer.maximumSize = Dimension(Int.MAX_VALUE, outer.preferredSize.height)
                block.revalidate()
                outer.revalidate()
                (outer.parent as? JComponent)?.revalidate()
                block.repaint()
                outer.repaint()
            }
        }
        addMouseListenerRecursively(header, toggleThinking)
        block.add(header, BorderLayout.NORTH); block.add(bodyScroll, BorderLayout.CENTER)
        outer.add(block, BorderLayout.CENTER)
        return capRowHeight(outer)
    }

    private fun updateThinkingBodySize(body: JTextArea, bodyScroll: JScrollPane, width: Int) {
        val textWidth = (width - 20).coerceAtLeast(80)
        body.setSize(textWidth, Int.MAX_VALUE)
        bodyScroll.preferredSize = Dimension(0, body.preferredSize.height.coerceIn(1, 140))
    }

    private fun addMouseListenerRecursively(
        component: Component,
        listener: java.awt.event.MouseListener
    ) {
        component.addMouseListener(listener)
        if (component is java.awt.Container) {
            component.components.forEach { addMouseListenerRecursively(it, listener) }
        }
    }

    private fun <T : JComponent> capRowHeight(row: T): T {
        row.putClientProperty("capHeight", true)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun refreshCappedHeight(component: Component) {
        if (component is JComponent && component.getClientProperty("capHeight") == true) {
            component.maximumSize =
                Dimension(component.maximumSize.width, component.preferredSize.height)
        }
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
                    val fence = line
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        if (buf.isNotEmpty()) buf.append("\n")
                        buf.append(lines[i]); i++
                    }
                    if (i < lines.size) {
                        i++
                        blocks.add(MarkdownBlock.CodeBlock(buf.toString()))
                    } else {
                        blocks.add(
                            MarkdownBlock.Paragraph(
                                listOf(
                                    fence,
                                    buf.toString()
                                ).filter { it.isNotEmpty() }.joinToString("\n")
                            )
                        )
                    }
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
