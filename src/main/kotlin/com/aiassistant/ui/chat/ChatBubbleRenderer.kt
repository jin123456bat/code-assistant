package com.aiassistant.ui.chat

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.RoundedBorder
import com.aiassistant.ui.toHtmlColor
import java.awt.BorderLayout
import java.awt.Font
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

    fun render(msg: ChatMessage): JComponent {
        return when (msg.type) {
            ChatMessage.Type.USER_TEXT -> renderUserBubble(msg)
            ChatMessage.Type.AGENT_TEXT -> renderAgentBubble(msg)
            ChatMessage.Type.ERROR -> renderErrorBubble(msg)
            ChatMessage.Type.SYSTEM -> renderSystemMsg(msg)
            ChatMessage.Type.TOOL_CALL -> renderToolCallPlaceholder(msg)
        }
    }

    private fun renderUserBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel(BorderLayout())
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
            // 限制最大宽度为容器 70%
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        wrapper.add(text, BorderLayout.EAST)
        wrapper.add(renderTimestamp(msg), BorderLayout.SOUTH)
        return wrapper
    }

    private fun renderAgentBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel(BorderLayout())
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val blocks = parseMarkdown(msg.content)
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    val rendered = block.text
                        .replace(Regex("`([^`]+)`")) {
                            val codeBgHex = AppColors.inlineCodeBg.toHtmlColor()
                            val codeBorderHex = AppColors.inlineCodeBorder.toHtmlColor()
                            "<code style='font-family:monospace;font-size:13px;background:${codeBgHex};padding:1px 6px;border-radius:3px;border:1px solid ${codeBorderHex}'>${it.groupValues[1]}</code>"
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
                }

                is MarkdownBlock.CodeBlock -> {
                    val code = JTextArea(block.code).apply {
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

                is MarkdownBlock.Header -> {
                    body.add(JLabel("<html><b style='font-size:14px'>${escapeHtml(block.text)}</b></html>"))
                }

                is MarkdownBlock.ListItem -> {
                    body.add(JLabel("<html>&nbsp;&nbsp;• ${escapeHtml(block.text)}</html>"))
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
                }
            }
        }

        wrapper.isOpaque = false
        wrapper.add(body, BorderLayout.CENTER)
        val tokenInfo =
            if (msg.tokenDelta != null) "↑${msg.tokenDelta.input / 1000}K ↓${msg.tokenDelta.output / 1000}K" else ""
        wrapper.add(renderTimestamp(msg, tokenInfo), BorderLayout.SOUTH)
        wrapper.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(12, 16, 12, 16),
            BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary)
        )
        return wrapper
    }

    private fun renderErrorBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel(BorderLayout())
        val retry = JButton("🔄 重试");
        val copy = JButton("📋 复制")
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
        val p = JPanel(BorderLayout())
        p.add(JLabel(msg.content, SwingConstants.CENTER).apply {
            foreground = AppColors.textTertiary; font = font.deriveFont(11f)
        }, BorderLayout.CENTER)
        return p
    }

    private fun renderToolCallPlaceholder(msg: ChatMessage): JPanel {
        return JPanel().apply {
            isOpaque = true
            add(JLabel("<html><i>🔧 ${msg.toolCall?.toolName ?: "tool"} — ${msg.toolCall?.state ?: ""}</i></html>"))
            background = AppColors.toolPlaceholderBg
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
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

    fun renderThinking(reasoning: String, durationMs: Long): JPanel {
        val block = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.thinkingBg; border =
            BorderFactory.createLineBorder(AppColors.thinkingBorder)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = AppColors.thinkingBg; border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JLabel("💭 思考过程"), BorderLayout.WEST)
            add(JLabel("${durationMs / 1000}.${(durationMs % 1000) / 100}s").apply {
                foreground = AppColors.thinkingTimeFg; font = font.deriveFont(10f)
            }, BorderLayout.EAST)
        }
        val body = JTextArea(reasoning.take(500)).apply {
            font = Font(Font.SANS_SERIF, Font.ITALIC, 12); foreground = AppColors.thinkingBodyFg
            background = AppColors.thinkingBg; isEditable = false; lineWrap = true
            border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        }
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                body.isVisible = !body.isVisible; block.revalidate()
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
