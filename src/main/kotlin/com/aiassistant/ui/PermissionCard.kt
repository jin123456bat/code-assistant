package com.aiassistant.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.border.AbstractBorder

/** diff 预览最多展示的行数，超出后折叠显示省略提示 */
private const val DIFF_PREVIEW_MAX_LINES = 40

/**
 * 权限确认卡片（M3-B 布局）。
 *
 * 布局：
 * - 头部行：▸/▾（展开/折叠）+ 工具名 + args 预览 + 右侧操作按钮（hover 显示）
 * - 展开区：diff 预览（仅 write_file）
 * - 点击操作后按钮消失，结果标签显示在右侧
 * - 危险变体（execute_command）：orange 边框 + ⚠ 标记，仅两个选项
 */
object PermissionCard {

    fun build(
        toolName: String,
        args: String,
        onAllowOnce: () -> Unit,
        onAlwaysAllow: () -> Unit,
        onReject: () -> Unit,
        diffLines: List<DiffLine>? = null
    ): JPanel {
        val isDanger = toolName == "execute_command"

        val argsPreview = args
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(120)
            .let { if (args.length > 120) "$it…" else it }

        // ---- 卡片外层 ----
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatTheme.toolBg
                g2.fillRoundRect(0, 0, width, height, ChatTheme.RADIUS, ChatTheme.RADIUS)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                CardBorder(if (isDanger) ChatTheme.danger else ChatTheme.toolBar),
                JBUI.Borders.empty(0, 0, 4, 0)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // ---- 折叠状态 ----
        val expanded = AtomicBoolean(false)
        var chosen = false
        val resultLabelRef = AtomicReference<JLabel>()

        // ---- 头部行 ----
        val chevronLabel = JLabel("▸").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
            preferredSize = Dimension(14, preferredSize.height)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // 左侧信息区：chevron + ⚠ + toolName + args
        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        infoPanel.add(chevronLabel)
        infoPanel.add(Box.createRigidArea(Dimension(4, 0)))
        if (isDanger) {
            infoPanel.add(JLabel("⚠ ").apply {
                font = ChatTheme.metaFont.deriveFont(Font.BOLD)
                foreground = ChatTheme.danger
            })
        }
        infoPanel.add(JLabel(toolName).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
        })
        if (argsPreview.isNotBlank()) {
            infoPanel.add(Box.createRigidArea(Dimension(6, 0)))
            infoPanel.add(JLabel(argsPreview).apply {
                font = ChatTheme.codeFont.deriveFont(ChatTheme.metaFont.size.toFloat())
                foreground = ChatTheme.textMuted
            })
        }

        // ---- 操作按钮（固定右侧，不随参数变长而被挤出） ----
        data class Btn(val text: String, val color: Color, val action: () -> Unit)
        val buttons = listOf(
            Btn("允许", ChatTheme.toolFg, onAllowOnce),
            Btn("始终允许", ChatTheme.doneCheck, onAlwaysAllow),
            Btn("拒绝", ChatTheme.error, onReject)
        )

        val buttonLabels = mutableListOf<JLabel>()
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = true
            background = ChatTheme.toolBg
        }

        fun buildButtons() {
            buttonPanel.removeAll()
            buttonLabels.clear()
            for (btn in buttons) {
                val lbl = JLabel(btn.text).apply {
                    font = ChatTheme.metaFont
                    foreground = btn.color
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    border = JBUI.Borders.empty(1, 5, 1, 5)
                }
                lbl.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        if (!chosen) {
                            chosen = true
                            btn.action()
                            for (b in buttonLabels) b.isVisible = false
                            val resultLbl = JLabel(btn.text).apply {
                                font = ChatTheme.metaFont.deriveFont(Font.BOLD)
                                foreground = btn.color
                                border = JBUI.Borders.empty(1, 5, 1, 5)
                            }
                            resultLabelRef.set(resultLbl)
                            buttonPanel.add(resultLbl)
                            buttonPanel.revalidate()
                            buttonPanel.repaint()
                        }
                    }
                    override fun mouseEntered(e: MouseEvent?) { lbl.foreground = ChatTheme.textPrimary }
                    override fun mouseExited(e: MouseEvent?) { lbl.foreground = btn.color }
                })
                buttonLabels.add(lbl)
                buttonPanel.add(lbl)
            }
        }
        buildButtons()

        // BorderLayout: 左侧信息截断，右侧按钮固定
        val headerRow = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 10, 6, 4)
        }
        headerRow.add(infoPanel, BorderLayout.CENTER)
        headerRow.add(buttonPanel, BorderLayout.EAST)

        // ---- chevron 点击展开/折叠 ----
        val northContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        northContainer.add(headerRow)

        val diffPanel = if (diffLines != null && diffLines.isNotEmpty()) {
            buildDiffBlock(diffLines).also { it.isVisible = false }
        } else null

        chevronLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded.set(!expanded.get())
                chevronLabel.text = if (expanded.get()) "▾" else "▸"
                diffPanel?.isVisible = expanded.get()
                chevronLabel.repaint()
                card.revalidate()
            }
        })

        if (diffPanel != null) northContainer.add(diffPanel)

        card.add(northContainer, BorderLayout.NORTH)

        // ---- 已选结果区（默认空） ----
        val resultRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 10, 4, 10)
        }
        card.add(resultRow, BorderLayout.SOUTH)

        return card
    }

    // ─── diff 预览块 ───

    private fun buildDiffBlock(diffLines: List<DiffLine>): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = ChatTheme.codeBg
            border = JBUI.Borders.empty(6, 10, 6, 10)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val displayLines = if (diffLines.size > DIFF_PREVIEW_MAX_LINES) {
            diffLines.take(DIFF_PREVIEW_MAX_LINES)
        } else {
            diffLines
        }

        for (line in displayLines) {
            val (prefix, color) = when (line.kind) {
                DiffKind.ADD -> "+ " to ChatTheme.diffAddFg
                DiffKind.DEL -> "- " to ChatTheme.diffDelFg
                DiffKind.CTX -> "  " to ChatTheme.textMuted
            }
            val text = (prefix + line.text).take(200)
            panel.add(JLabel(text).apply {
                font = ChatTheme.codeFont.deriveFont(ChatTheme.metaFont.size.toFloat())
                foreground = color
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        if (diffLines.size > DIFF_PREVIEW_MAX_LINES) {
            val omitted = diffLines.size - DIFF_PREVIEW_MAX_LINES
            panel.add(JLabel("… (省略 $omitted 行)").apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.textMuted
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        return panel
    }

    // ─── 卡片圆角边框 ───

    private class CardBorder(private val color: Color) : AbstractBorder() {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(x, y, w - 1, h - 1, ChatTheme.RADIUS, ChatTheme.RADIUS)
            g2.dispose()
        }

        override fun getBorderInsets(c: Component): Insets = Insets(1, 1, 1, 1)
        override fun getBorderInsets(c: Component, insets: Insets): Insets {
            insets.set(1, 1, 1, 1)
            return insets
        }
        override fun isBorderOpaque(): Boolean = false
    }
}
