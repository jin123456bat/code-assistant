package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

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
        onFeedback: ((String) -> Unit)? = null,
        panelWidth: Int = 0
    ): JComponent {
        return when (msg.type) {
            ChatMessage.Type.USER_TEXT -> renderUserBubble(msg)
            ChatMessage.Type.AGENT_TEXT -> renderAgentBubble(msg, onFeedback, panelWidth)
            ChatMessage.Type.ERROR -> renderErrorBubble(msg, onRetry)
            ChatMessage.Type.SYSTEM -> renderSystemMsg(msg)
            ChatMessage.Type.TOOL_CALL -> renderToolCallPlaceholder(msg)
        }
    }

    private fun renderUserBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "user")
            accessibleContext.accessibleDescription = "用户消息: ${msg.content.take(100)}"
        }
        val text = JLabel(
            "<html><body style='width:100%;max-width:480px'>${
                escapeHtml(msg.content).replace(
                    "\n",
                    "<br>"
                )
            }</body></html>"
        ).apply {
            isOpaque = true; background = AppColors.userBubbleBg; font = font.deriveFont(14f)
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(12, AppColors.userBubbleBg),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)
            )
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        wrapper.add(text, BorderLayout.EAST)
        wrapper.add(renderTimestamp(msg), BorderLayout.SOUTH)
        return wrapper
    }

    private fun renderAgentBubble(
        msg: ChatMessage,
        onFeedback: ((String) -> Unit)? = null,
        panelWidth: Int = 0
    ): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "agent")
        }
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

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
                            "<html><body style='width:100%;max-width:520px'>${
                                escapeHtml(
                                    rendered
                                ).replace("\n", "<br>")
                            }</body></html>"
                        ).apply {
                            font = font.deriveFont(14f)
                            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
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
                            val code = JTextArea(cb.code).apply {
                                font = monoFont
                                background = AppColors.codeBg; foreground = AppColors.textSecondary
                                border = BorderFactory.createCompoundBorder(
                                    RoundedBorder(8, AppColors.codeBorder),
                                    BorderFactory.createEmptyBorder(8, 12, 8, 12)
                                )
                                isEditable = false; lineWrap = false
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
                                    BorderFactory.createEmptyBorder(8, 12, 8, 12)
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
                        val code = JTextArea(cb.code).apply {
                            font = monoFont
                            background = AppColors.codeBg; foreground = AppColors.textSecondary
                            border = BorderFactory.createCompoundBorder(
                                RoundedBorder(8, AppColors.codeBorder),
                                BorderFactory.createEmptyBorder(8, 12, 8, 12)
                            )
                            isEditable = false; lineWrap = false
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
                        font = Font(Font.SANS_SERIF, Font.ITALIC, 13); foreground =
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
        wrapper.add(body, BorderLayout.CENTER)

        // 底部行：左边时间戳+token信息，右边反馈按钮
        val bottomRow = JPanel(BorderLayout())
        bottomRow.isOpaque = false
        val tokenInfo =
            if (msg.tokenDelta != null) "↑${msg.tokenDelta.input / 1000}K ↓${msg.tokenDelta.output / 1000}K" else ""
        bottomRow.add(renderTimestamp(msg, tokenInfo), BorderLayout.WEST)

        // 反馈按钮（对齐 docs/ui/chat.md §十：每条 assistant 消息右下角附加 👍/👎 按钮）
        if (onFeedback != null && msg.feedback == null) {
            val feedbackPanel = JPanel()
            feedbackPanel.isOpaque = false
            val thumbsUp = JButton("👍").apply {
                font = font.deriveFont(12f)
                isOpaque = false
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
                toolTipText = "有帮助"
                accessibleContext.accessibleDescription = "这条回答有帮助"
                addActionListener { onFeedback("positive") }
            }
            val thumbsDown = JButton("👎").apply {
                font = font.deriveFont(12f)
                isOpaque = false
                isContentAreaFilled = false
                border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
                toolTipText = "无帮助"
                accessibleContext.accessibleDescription = "这条回答没有帮助"
                addActionListener { onFeedback("negative") }
            }
            feedbackPanel.add(thumbsUp)
            feedbackPanel.add(thumbsDown)
            bottomRow.add(feedbackPanel, BorderLayout.EAST)
        } else if (msg.feedback != null) {
            // 已反馈：显示当前反馈状态（灰色禁用态）
            val feedbackLabel = JLabel(
                if (msg.feedback == "positive") "👍" else "👎"
            ).apply {
                font = font.deriveFont(12f)
                foreground = AppColors.textTertiary
                border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
            }
            bottomRow.add(feedbackLabel, BorderLayout.EAST)
        }
        wrapper.add(bottomRow, BorderLayout.SOUTH)
        wrapper.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(12, 16, 12, 16),
            BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary)
        )
        return wrapper
    }

    private fun renderErrorBubble(msg: ChatMessage, onRetry: (() -> Unit)?): JPanel {
        val wrapper = JPanel(BorderLayout()).apply {
            putClientProperty("bubbleType", "error")
            accessibleContext.accessibleDescription = "错误消息: ${msg.content.take(100)}"
        }
        val retry = JButton("🔄 重试").apply {
            isEnabled = onRetry != null
            accessibleContext.accessibleDescription = "重新发送失败的消息"
            if (onRetry != null) addActionListener { onRetry() }
        }
        val copy = JButton("📋 复制").apply {
            accessibleContext.accessibleDescription = "复制错误消息到剪贴板"
            addActionListener {
                runCatching {
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(msg.content), null)
                }
            }
        }
        val btns = JPanel().apply { add(retry); add(copy) }
        wrapper.add(JLabel("<html><body style='width:100%;max-width:480px'>❌ ${escapeHtml(msg.content)}</body></html>").apply {
            isOpaque = true; background = AppColors.errorBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 16, 12, 16),
                BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.error)
            )
        }, BorderLayout.CENTER)
        wrapper.add(btns, BorderLayout.SOUTH)
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
                foreground = AppColors.textSecondary; font = font.deriveFont(11f)
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
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // 复用现有 Agent 气泡渲染逻辑
        val renderedBubble = render(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = markdownText,
                timestamp = java.time.Instant.now()
            ),
            onFeedback = null
        )
        wrapper.add(renderedBubble)

        // 闪烁光标 ▍，颜色 #3B82F6 (AppColors.primary)，500ms 闪烁
        val cursorLabel = JLabel("▍").apply {
            foreground = AppColors.primary
            font = font.deriveFont(14f)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 16, 0, 0)
        }
        wrapper.add(cursorLabel)

        val blinkTimer = javax.swing.Timer(500) {
            cursorLabel.isVisible = !cursorLabel.isVisible
        }
        blinkTimer.start()

        // 当 wrapper 从父容器移除时停止 timer，避免内存泄漏
        wrapper.addHierarchyListener {
            if (it.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
                if (!wrapper.isDisplayable) {
                    blinkTimer.stop()
                }
            }
        }

        return wrapper
    }

    /**
     * 渲染思考过程折叠块（对齐 docs/ui/chat.md §三）。
     * 默认折叠，">" 箭头可点击展开/折叠，显示完整思考内容。
     */
    fun renderThinking(reasoning: String, durationMs: Long): JPanel {
        val block = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.thinkingBg; border =
            BorderFactory.createLineBorder(AppColors.thinkingBorder)
        }
        // 箭头 label：默认折叠显示 "▶"，展开后显示 "▾"
        val arrowLabel = JLabel("▶").apply {
            foreground = AppColors.thinkingBodyFg
            font = font.deriveFont(12f)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 4)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.thinkingBg; border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            val leftPanel = JPanel().apply {
                isOpaque = true; background = AppColors.thinkingBg
                add(arrowLabel)
                add(JLabel("💭 思考过程").apply {
                    foreground = AppColors.thinkingBodyFg
                })
            }
            add(leftPanel, BorderLayout.WEST)
            add(JLabel("${durationMs / 1000}.${(durationMs % 1000) / 100}s").apply {
                foreground = AppColors.thinkingTimeFg; font = font.deriveFont(10f)
            }, BorderLayout.EAST)
        }
        val body = JTextArea(reasoning).apply {
            font = Font(Font.SANS_SERIF, Font.ITALIC, 12); foreground = AppColors.thinkingBodyFg
            background = AppColors.thinkingBg; isEditable = false; lineWrap = true
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
            // 默认折叠：body 初始隐藏
            isVisible = false
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val expand = !body.isVisible
                body.isVisible = expand
                arrowLabel.text = if (expand) "▾" else "▶"
                block.revalidate()
                block.repaint()
            }
        })
        block.add(header, BorderLayout.NORTH); block.add(body, BorderLayout.CENTER)
        return block
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
