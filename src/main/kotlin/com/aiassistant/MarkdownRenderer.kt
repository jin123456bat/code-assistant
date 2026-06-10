package com.aiassistant

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Renders markdown content as a Swing JPanel.
 * Uses org.jetbrains:markdown for parsing and generates
 * styled HTML. Code blocks get syntax-highlighted EditorTextField panels.
 */
class MarkdownRenderer {

    private val flavour = GFMFlavourDescriptor()

    fun render(markdown: String): JPanel {
        val container = JPanel()
        container.layout = javax.swing.BoxLayout(container, javax.swing.BoxLayout.Y_AXIS)

        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val html = try {
            HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
        } catch (e: Exception) {
            markdown.replace("\n", "<br>")
        }

        val textPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            border = JBUI.Borders.empty()
            background = container.background
        }
        val styledHtml = buildStyledHtml(html, textPane)
        textPane.text = styledHtml
        textPane.caretPosition = 0
        container.add(textPane)

        return container
    }

    /**
     * 原地更新已渲染的 panel 内容，避免 remove/add 触发布局震荡。
     * @return true 如果高度变化超过阈值（调用方需要 revalidate）
     */
    fun updateInPlace(container: JPanel, markdown: String): Boolean {
        if (container.componentCount == 0) return false
        val textPane = container.components[0] as? JTextPane ?: return false
        val oldHeight = container.preferredSize?.height ?: 0

        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val html = try {
            HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
        } catch (e: Exception) {
            markdown.replace("\n", "<br>")
        }
        textPane.text = buildStyledHtml(html, textPane)
        textPane.caretPosition = textPane.document.length

        container.invalidate()
        val newHeight = container.preferredSize?.height ?: 0
        return kotlin.math.abs(newHeight - oldHeight) > 10
    }

    fun renderForStreaming(markdown: String, parentBackground: java.awt.Color): JPanel {
        val container = JPanel()
        container.layout = javax.swing.BoxLayout(container, javax.swing.BoxLayout.Y_AXIS)
        container.background = parentBackground

        val parsedTree = try {
            MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        } catch (e: Exception) {
            null
        }

        val html = if (parsedTree != null) {
            try {
                HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
            } catch (e: Exception) {
                markdown.replace("\n", "<br>")
            }
        } else {
            markdown.replace("\n", "<br>")
        }

        val textPane = JTextPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            border = JBUI.Borders.empty()
            background = parentBackground
        }
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
                background = JBColor(0xF0F0F0, 0x32323A)
                border = JBUI.Borders.empty(4, 8)
            }
            val langLabel = JLabel(language.uppercase()).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = JBColor(0x888888, 0x999999)
            }
            val copyButton = JButton("Copy").apply {
                font = font.deriveFont(Font.PLAIN, 10f)
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
                background = JBColor(0xFAFAFA, 0x2B2B2B)
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
                    font-size: ${fontSize}px;
                    color: #$fgHex;
                    background-color: #$bgHex;
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
                h1 { font-size: ${fontSize + 8}px; margin: 12px 0 4px 0; }
                h2 { font-size: ${fontSize + 4}px; margin: 12px 0 4px 0; }
                h3 { font-size: ${fontSize + 2}px; margin: 12px 0 4px 0; }
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
                a { color: #2674B4; }
                hr { border: none; border-top: 1px solid $borderColor; }
            </style></head><body>$htmlBody</body></html>
        """.trimIndent()
    }
}
