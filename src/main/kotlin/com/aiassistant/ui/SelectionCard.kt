package com.aiassistant.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * ask_user 工具的选择卡片（M5-A）。
 *
 * 外观与 [PermissionCard] 一致：
 * - 圆角卡片，toolBg 淡填充 + toolBar 色边框
 * - 头部行：问题文字（粗体 toolFg）
 * - 选项列表（每行可点击，hover 高亮，第一项默认高亮）：
 *     ❯ 选项文字
 * - 点击后整张卡切换为"已选择"状态，禁止二次点击
 *
 * 用法：
 * ```kotlin
 * val card = SelectionCard.build(
 *     question = "你想怎么做？",
 *     options  = listOf("方案 A", "方案 B"),
 *     onChosen = { chosen -> result.set(chosen); latch.countDown() }
 * )
 * conversationContainer.add(card, conversationContainer.componentCount - 1)
 * conversationContainer.revalidate()
 * conversationContainer.repaint()
 * ```
 */
object SelectionCard {

    /**
     * 构建选择卡片面板。
     *
     * @param question 展示在头部的问题文字
     * @param options  选项列表（至少一项）
     * @param onChosen 用户点击某项后的回调，参数为所选文字；只会触发一次
     * @return 可直接插入 conversationContainer 的 JPanel
     */
    fun build(
        question: String,
        options: List<String>,
        onChosen: (String) -> Unit
    ): JPanel {
        // ---- 卡片外层（圆角 + toolBg 背景）----
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
                CardBorder(ChatTheme.toolBar),
                JBUI.Borders.empty(0, 0, 6, 0)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // ---- 头部行：问题文字 ----
        val headerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 6, 10)
        }
        headerRow.add(JLabel(question).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
        })
        headerRow.add(Box.createHorizontalGlue())
        card.add(headerRow, BorderLayout.NORTH)

        // ---- 选项列表 ----
        val optionList = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 2, 6)
        }

        // 只允许选一次
        var chosen = false

        options.forEachIndexed { idx, option ->
            val isDefault = idx == 0
            val row = buildOptionRow(option, isDefault) {
                if (!chosen) {
                    chosen = true
                    showConfirmedState(card, option)
                    onChosen(option)
                }
            }
            optionList.add(row)
        }

        card.add(optionList, BorderLayout.CENTER)
        return card
    }

    // ---- 私有辅助 ----

    /**
     * 构建单个选项行，与 PermissionCard.buildOptionRow 视觉一致。
     */
    private fun buildOptionRow(
        option: String,
        isDefault: Boolean,
        onClick: () -> Unit
    ): JPanel {
        var hovered = isDefault

        val row = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                if (hovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ChatTheme.toolBg
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 4, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        // Chevron（默认项显示 ❯，其他空格占位）
        val chevron = JLabel(if (isDefault) "❯" else " ").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
            preferredSize = Dimension(14, preferredSize.height)
            minimumSize = Dimension(14, minimumSize.height)
            maximumSize = Dimension(14, Int.MAX_VALUE)
            border = JBUI.Borders.empty(0, 2, 0, 6)
        }
        inner.add(chevron)

        val primaryLabel = JLabel(option).apply {
            font = ChatTheme.metaFont
            foreground = if (isDefault) ChatTheme.textPrimary else ChatTheme.textSecondary
            alignmentX = Component.LEFT_ALIGNMENT
        }
        inner.add(primaryLabel)
        inner.add(Box.createHorizontalGlue())

        row.add(inner, BorderLayout.CENTER)

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                chevron.text = "❯"
                primaryLabel.foreground = ChatTheme.textPrimary
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = isDefault
                chevron.text = if (isDefault) "❯" else " "
                primaryLabel.foreground = if (isDefault) ChatTheme.textPrimary else ChatTheme.textSecondary
                row.repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }
        })

        return row
    }

    /**
     * 将卡片 CENTER 区域替换为"已选择"静态状态。
     * 保留头部（问题文字），移除选项列表，插入确认标签。
     */
    private fun showConfirmedState(card: JPanel, chosenOption: String) {
        val centerComp = (card.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
        if (centerComp != null) card.remove(centerComp)

        val confirmedRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 14, 6, 10)
        }
        confirmedRow.add(JLabel("已选择: $chosenOption ✓").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.doneCheck
        })
        confirmedRow.add(Box.createHorizontalGlue())

        card.add(confirmedRow, BorderLayout.CENTER)
        card.revalidate()
        card.repaint()
    }

    // ---- 圆角边框（与 PermissionCard.CardBorder 相同实现）----

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
