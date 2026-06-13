package com.aiassistant.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.AbstractBorder

/**
 * ask_user 工具的选择卡片（M5-A / M5-B 多选扩展）。
 *
 * 外观与审批选择卡一致：
 * - 圆角卡片，toolBg 淡填充 + toolBar 色边框
 * - 头部行：问题文字（粗体 toolFg）
 *
 * 单选模式（默认，multiSelect=false）：
 *   - 选项列表（每行可点击，hover 高亮，第一项默认高亮）：
 *       ❯ 选项文字
 *   - 点击后整张卡切换为"已选择"状态，禁止二次点击
 *
 * 多选模式（multiSelect=true）：
 *   - 每个选项行前显示复选框（☐/☑），点击切换勾选状态
 *   - 底部有"确认 (N)"按钮，N = 已勾选数量；0 个已选时按钮禁用
 *   - 点击确认后整张卡切换为"已选择: a, b ✓"状态，禁止二次交互
 *
 * 用法：
 * ```kotlin
 * // 单选
 * val card = SelectionCard.build(
 *     question = "你想怎么做？",
 *     options  = listOf("方案 A", "方案 B"),
 *     onConfirm = { choices -> result.set(choices.joinToString(", ")); latch.countDown() }
 * )
 * // 多选
 * val card = SelectionCard.build(
 *     question    = "请选择要处理的功能：",
 *     options     = listOf("功能A", "功能B", "功能C"),
 *     multiSelect = true,
 *     onConfirm   = { choices -> result.set(choices.joinToString(", ")); latch.countDown() }
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
     * @param question    展示在头部的问题文字
     * @param options     选项列表（至少一项）
     * @param multiSelect false（默认）= 单选，点击即提交；true = 多选，复选框 + 确认按钮
     * @param onConfirm   用户确认后的回调，参数为所选项列表；只会触发一次。
     *                    单选时列表长度始终为 1；多选时可为 1 到 N 项。
     * @return 可直接插入 conversationContainer 的 JPanel
     */
    fun build(
        question: String,
        options: List<String>,
        multiSelect: Boolean = false,
        onConfirm: (List<String>) -> Unit
    ): JPanel {
        return if (multiSelect) {
            buildMultiSelect(question, options, onConfirm)
        } else {
            buildSingleSelect(question, options) { chosen -> onConfirm(listOf(chosen)) }
        }
    }

    /**
     * 向下兼容的单选入口（保留旧调用签名）。
     * 内部委托给 [build]，onChosen 回调包装为单项列表再转发。
     */
    fun build(
        question: String,
        options: List<String>,
        onChosen: (String) -> Unit
    ): JPanel = build(question, options, multiSelect = false) { choices ->
        onChosen(choices.firstOrNull() ?: "")
    }

    // ---- 单选构建 ----

    private fun buildSingleSelect(
        question: String,
        options: List<String>,
        onChosen: (String) -> Unit
    ): JPanel {
        val card = makeCard()

        // ---- 头部行：问题文字 ----
        card.add(makeHeader(question), BorderLayout.NORTH)

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
            val row = buildSingleOptionRow(option, isDefault) {
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

    // ---- 多选构建 ----

    private fun buildMultiSelect(
        question: String,
        options: List<String>,
        onConfirm: (List<String>) -> Unit
    ): JPanel {
        val card = makeCard()

        // ---- 头部行：问题文字 ----
        card.add(makeHeader(question), BorderLayout.NORTH)

        // ---- 复选框状态追踪：每个选项对应一个勾选状态 ----
        val checkedStates = Array(options.size) { false }

        // ---- 确认按钮（初始禁用，0 选时禁用）----
        val confirmBtn = JButton("确认 (0)").apply {
            font = ChatTheme.metaFont
            isEnabled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(4, 12, 4, 12)
        }

        // 更新确认按钮文案 + 启用状态
        fun refreshConfirmBtn() {
            val count = checkedStates.count { it }
            confirmBtn.text = "确认 ($count)"
            confirmBtn.isEnabled = count > 0
            confirmBtn.repaint()
        }

        // ---- 选项列表（复选框行）----
        val optionList = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 2, 6)
        }

        options.forEachIndexed { idx, option ->
            val row = buildCheckboxRow(option, checkedStates, idx) {
                refreshConfirmBtn()
            }
            optionList.add(row)
        }

        // ---- 底部：确认按钮行 ----
        val bottomRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 10, 6, 10)
        }
        bottomRow.add(Box.createHorizontalGlue())
        bottomRow.add(confirmBtn)

        // ---- 确认按钮点击逻辑 ----
        var confirmed = false
        confirmBtn.addActionListener {
            if (!confirmed) {
                confirmed = true
                val selected = options.filterIndexed { idx, _ -> checkedStates[idx] }
                val displayText = selected.joinToString(", ")
                showConfirmedState(card, displayText)
                onConfirm(selected)
            }
        }

        // 将选项列表 + 按钮行打包到 CENTER
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        centerPanel.add(optionList)
        centerPanel.add(bottomRow)

        card.add(centerPanel, BorderLayout.CENTER)
        return card
    }

    // ---- 私有辅助 ----

    /** 创建统一样式的卡片外层面板 */
    private fun makeCard(): JPanel = object : JPanel(BorderLayout()) {
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

    /** 创建头部问题标签行 */
    private fun makeHeader(question: String): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 6, 10)
        add(JLabel(question).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
        })
        add(Box.createHorizontalGlue())
    }

    /**
     * 单选模式：构建单个选项行。
     */
    private fun buildSingleOptionRow(
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
     * 多选模式：构建单个复选框选项行。
     *
     * @param option        选项文字
     * @param checkedStates 所有选项的勾选状态数组（共享引用）
     * @param idx           当前选项在数组中的索引
     * @param onToggle      每次勾选/取消后回调，用于刷新确认按钮
     */
    private fun buildCheckboxRow(
        option: String,
        checkedStates: Array<Boolean>,
        idx: Int,
        onToggle: () -> Unit
    ): JPanel {
        var hovered = false

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

        // 复选框符号标签（☐ 未选 / ☑ 已选），用 toolFg/doneCheck 两色区分
        val checkLabel = JLabel("☐").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
            preferredSize = Dimension(16, preferredSize.height)
            minimumSize = Dimension(16, minimumSize.height)
            maximumSize = Dimension(16, Int.MAX_VALUE)
            border = JBUI.Borders.empty(0, 2, 0, 6)
        }
        inner.add(checkLabel)

        val primaryLabel = JLabel(option).apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.textSecondary
            alignmentX = Component.LEFT_ALIGNMENT
        }
        inner.add(primaryLabel)
        inner.add(Box.createHorizontalGlue())

        row.add(inner, BorderLayout.CENTER)

        // 切换勾选状态并刷新外观
        fun toggleCheck() {
            checkedStates[idx] = !checkedStates[idx]
            if (checkedStates[idx]) {
                checkLabel.text = "☑"
                checkLabel.foreground = ChatTheme.doneCheck
                primaryLabel.foreground = ChatTheme.textPrimary
            } else {
                checkLabel.text = "☐"
                checkLabel.foreground = ChatTheme.toolFg
                primaryLabel.foreground = ChatTheme.textSecondary
            }
            row.repaint()
            onToggle()
        }

        row.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                row.repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                toggleCheck()
            }
        })

        return row
    }

    /**
     * 将卡片 CENTER 区域替换为"已选择"静态状态。
     * 保留头部（问题文字），移除选项列表，插入确认标签。
     * 单选和多选共用此方法（传入的 displayText 已格式化好）。
     */
    private fun showConfirmedState(card: JPanel, displayText: String) {
        val centerComp = (card.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
        if (centerComp != null) card.remove(centerComp)

        val confirmedRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 14, 6, 10)
        }
        confirmedRow.add(JLabel("已选择: $displayText ✓").apply {
            font = ChatTheme.metaFont
            foreground = ChatTheme.doneCheck
        })
        confirmedRow.add(Box.createHorizontalGlue())

        card.add(confirmedRow, BorderLayout.CENTER)
        card.revalidate()
        card.repaint()
    }

    // ---- 圆角边框 ----

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
