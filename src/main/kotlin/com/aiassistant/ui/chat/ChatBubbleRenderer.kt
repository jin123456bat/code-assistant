package com.aiassistant.ui.chat

import com.aiassistant.ui.RoundedBorder
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.*

// 聊天气泡渲染 — 支持 Markdown + 亮/暗主题

object ChatBubbleRenderer {

    // ---- 主题色板 ----
    // 用户气泡
    private val userBubbleBg = JBColor(0xE8F0FE, 0x1E3A5F)

    // Agent 左侧蓝条
    private val agentAccent = JBColor(0x3B82F6, 0x60A5FA)

    // 错误气泡
    private val errorBg = JBColor(0xFEE2E2, 0x7F1D1D)
    private val errorAccent = JBColor(0xEF4444, 0xF87171)

    // 代码块
    private val codeBg = JBColor(0xF6F8FA, 0x161B22)
    private val codeBorder = JBColor(0xE1E4E8, 0x30363D)

    // 引用块
    private val quoteBg = JBColor(0xF9FAFB, 0x111827)
    private val quoteBorder = JBColor(0xD1D5DB, 0x4B5563)

    // 系统消息
    private val systemFg = JBColor(0x94A3B8, 0x6B7280)

    // 时间戳
    private val timestampFg = JBColor(0x6B7280, 0x9CA3AF)

    // 工具调用占位
    private val toolPlaceholderBg = JBColor(0xF3F4F6, 0x1F2937)

    // 思考过程
    private val thinkingBg = JBColor(0xFFF8F0, 0x3D2E1C)
    private val thinkingBorder = JBColor(0xFDE8D0, 0x5C3D1E)
    private val thinkingTimeFg = JBColor(0xB45309, 0xF59E0B)
    private val thinkingBodyFg = JBColor(0x92400E, 0xFBBF24)

    // 内联代码
    private val inlineCodeBg = JBColor(0xF6F8FA, 0x1F2937)
    private val inlineCodeBorder = JBColor(0xE1E4E8, 0x374151)

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
            isOpaque = true; background = userBubbleBg; font = font.deriveFont(14f)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
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
                            "<code style='font-family:monospace;font-size:13px;background:${
                                colorToHex(
                                    inlineCodeBg
                                )
                            };padding:1px 6px;border-radius:3px;border:1px solid ${
                                colorToHex(
                                    inlineCodeBorder
                                )
                            }'>${it.groupValues[1]}</code>"
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
                        font = Font("Monospaced", Font.PLAIN, 13)
                        background = codeBg
                        border = BorderFactory.createCompoundBorder(
                            RoundedBorder(8, codeBorder),
                            BorderFactory.createEmptyBorder(8, 12, 8, 12)
                        )
                        isEditable = false; lineWrap = false
                    }
                    body.add(code)
                }

                is MarkdownBlock.Header -> {
                    body.add(JLabel("<html><b style='font-size:14px'>${escapeHtml(block.text)}</b></html>"))
                }

                is MarkdownBlock.ListItem -> {
                    body.add(JLabel("<html>&nbsp;&nbsp;• ${escapeHtml(block.text)}</html>"))
                }

                is MarkdownBlock.QuoteBlock -> {
                    body.add(JTextArea(block.text).apply {
                        font = Font("SansSerif", Font.ITALIC, 13); foreground = timestampFg
                        background = quoteBg; isEditable = false; lineWrap = true
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 3, 0, 0, quoteBorder),
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
            BorderFactory.createMatteBorder(0, 3, 0, 0, agentAccent)
        )
        return wrapper
    }

    private fun renderErrorBubble(msg: ChatMessage): JPanel {
        val wrapper = JPanel(BorderLayout())
        val retry = JButton("🔄 重试");
        val copy = JButton("📋 复制")
        val btns = JPanel().apply { add(retry); add(copy) }
        wrapper.add(JLabel("<html><body style='width:100%;max-width:480px'>❌ ${escapeHtml(msg.content)}</body></html>").apply {
            isOpaque = true; background = errorBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 16, 12, 16),
                BorderFactory.createMatteBorder(0, 3, 0, 0, errorAccent)
            )
        }, BorderLayout.CENTER)
        wrapper.add(btns, BorderLayout.SOUTH)
        return wrapper
    }

    private fun renderSystemMsg(msg: ChatMessage): JPanel {
        val p = JPanel(BorderLayout())
        p.add(JLabel(msg.content, SwingConstants.CENTER).apply {
            foreground = systemFg; font = font.deriveFont(11f)
        }, BorderLayout.CENTER)
        return p
    }

    private fun renderToolCallPlaceholder(msg: ChatMessage): JPanel {
        return JPanel().apply {
            isOpaque = true
            add(JLabel("<html><i>🔧 ${msg.toolCall?.toolName ?: "tool"} — ${msg.toolCall?.state ?: ""}</i></html>"))
            background = toolPlaceholderBg
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
                foreground = timestampFg; font = font.deriveFont(11f)
            }, BorderLayout.EAST)
        }
    }

    fun renderThinking(reasoning: String, durationMs: Long): JPanel {
        val block = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = thinkingBg; border = BorderFactory.createLineBorder(thinkingBorder)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 120)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = thinkingBg; border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(JLabel("💭 思考过程"), BorderLayout.WEST)
            add(JLabel("${durationMs / 1000}.${(durationMs % 1000) / 100}s").apply {
                foreground = thinkingTimeFg; font = font.deriveFont(10f)
            }, BorderLayout.EAST)
        }
        val body = JTextArea(reasoning.take(500)).apply {
            font = Font("SansSerif", Font.ITALIC, 12); foreground = thinkingBodyFg
            background = thinkingBg; isEditable = false; lineWrap = true
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

    // ponytail: JBColor 转 HTML 颜色（用于内联 CSS），fallback 到亮色值
    private fun colorToHex(c: Color): String {
        val rgb = c.rgb and 0xFFFFFF
        return "#${rgb.toString(16).padStart(6, '0')}"
    }
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
    data class Header(val text: String) : MarkdownBlock()
    data class ListItem(val text: String) : MarkdownBlock()
    data class QuoteBlock(val text: String) : MarkdownBlock()
}
