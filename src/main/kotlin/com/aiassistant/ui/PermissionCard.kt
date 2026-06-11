package com.aiassistant.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.AbstractBorder

/** diff 预览最多展示的行数，超出后折叠显示省略提示 */
private const val DIFF_PREVIEW_MAX_LINES = 40

/**
 * 权限确认选项列表卡片（M3-A）。
 *
 * 外观：
 * - 圆角卡片，toolBg 淡填充 + toolBar/danger 色边框
 * - 头部行：工具名（粗体 toolFg）+ args 预览（等宽 textMuted，单行截断）
 * - 选项列表（三个可点击行，hover 高亮，默认高亮第一项）：
 *     [0] ❯ 允许本次                   → onAllowOnce()
 *     [1]   允许，且不再询问 <toolName>  → onAlwaysAllow()
 *     [2]   拒绝并说明                  → onReject()
 * - 危险变体（execute_command）：orange 边框 + ⚠ 标记，仅展示前两个选项
 * - 点击后整张卡片切换为"已确认"状态（标签 + 非交互）
 *
 * 用法：
 * ```kotlin
 * val card = PermissionCard.build(
 *     toolName = name,
 *     args = args,
 *     onAllowOnce = { userChoice.set(true); latch.countDown() },
 *     onAlwaysAllow = { AppSettingsService.getInstance().addToolToWhitelist(name)
 *                       userChoice.set(true); latch.countDown() },
 *     onReject = { userChoice.set(false); latch.countDown() }
 * )
 * ```
 */
object PermissionCard {

    /**
     * 构建权限确认卡片面板。
     *
     * @param toolName    被确认的工具名
     * @param args        工具参数字符串（可能很长，内部自动截断）
     * @param onAllowOnce 用户选择"允许本次"时的回调
     * @param onAlwaysAllow 用户选择"始终允许"时的回调（已含白名单写入）
     * @param onReject    用户选择"拒绝"时的回调
     * @return 可直接插入 conversationContainer 的 JPanel
     */
    fun build(
        toolName: String,
        args: String,
        onAllowOnce: () -> Unit,
        onAlwaysAllow: () -> Unit,
        onReject: () -> Unit,
        diffLines: List<DiffLine>? = null
    ): JPanel {
        // 危险变体：execute_command 使用 orange 样式
        val isDanger = toolName == "execute_command"

        // args 预览：最多 120 个字符，单行
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
                // 淡背景填充
                g2.color = ChatTheme.toolBg
                g2.fillRoundRect(0, 0, width, height, ChatTheme.RADIUS, ChatTheme.RADIUS)
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                CardBorder(if (isDanger) ChatTheme.danger else ChatTheme.toolBar),
                JBUI.Borders.empty(0, 0, 6, 0)
            )
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // ---- 头部行 ----
        val headerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 6, 10)
        }

        // 危险标记
        if (isDanger) {
            headerRow.add(JLabel("⚠ ").apply {
                font = ChatTheme.metaFont.deriveFont(Font.BOLD)
                foreground = ChatTheme.danger
            })
        }

        // 工具名（粗体 toolFg）
        headerRow.add(JLabel(toolName).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
        })

        // args 预览（等宽 textMuted）
        if (argsPreview.isNotBlank()) {
            headerRow.add(Box.createRigidArea(Dimension(6, 0)))
            headerRow.add(JLabel(argsPreview).apply {
                font = ChatTheme.codeFont.deriveFont(ChatTheme.metaFont.size.toFloat())
                foreground = ChatTheme.textMuted
                // 单行截断（过长时用…）
                maximumSize = Dimension(400, preferredSize.height)
            })
        }

        headerRow.add(Box.createHorizontalGlue())

        // 北区：头部行（若有 diff 则用纵向堆叠面板包裹头部 + diff 区）
        if (diffLines != null && diffLines.isNotEmpty()) {
            val northPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            northPanel.add(headerRow)
            northPanel.add(buildDiffBlock(diffLines))
            card.add(northPanel, BorderLayout.NORTH)
        } else {
            card.add(headerRow, BorderLayout.NORTH)
        }

        // ---- 选项列表 ----
        val optionList = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 2, 6)
        }

        // 定义选项数据（危险变体只有两个选项）
        data class OptionDef(
            val primary: String,
            val sub: String?,
            val action: () -> Unit,
            val confirmedLabel: String,
            val isDefault: Boolean = false
        )

        // 定义选项数据（危险变体只有两个选项）
        val options: List<OptionDef> = if (isDanger) {
            listOf(
                OptionDef(
                    primary = "允许本次",
                    sub = null,
                    action = onAllowOnce,
                    confirmedLabel = "已允许 ✓",
                    isDefault = true
                ),
                OptionDef(
                    primary = "拒绝并说明",
                    sub = "回到输入框告诉 AI 换个做法",
                    action = onReject,
                    confirmedLabel = "已拒绝 ✗"
                )
            )
        } else {
            listOf(
                OptionDef(
                    primary = "允许本次",
                    sub = null,
                    action = onAllowOnce,
                    confirmedLabel = "已允许 ✓",
                    isDefault = true
                ),
                OptionDef(
                    primary = "允许，且不再询问 $toolName",
                    sub = "加入白名单，后续此工具自动放行",
                    action = onAlwaysAllow,
                    confirmedLabel = "已加入白名单 ✓"
                ),
                OptionDef(
                    primary = "拒绝并说明",
                    sub = "回到输入框告诉 AI 换个做法",
                    action = onReject,
                    confirmedLabel = "已拒绝 ✗"
                )
            )
        }

        // 已选择状态标记（只允许选一次）
        var chosen = false

        // 构建每一个选项行并加入列表
        options.forEach { opt ->
            val row = buildOptionRow(
                option = opt.primary,
                subText = opt.sub,
                isDefault = opt.isDefault
            ) {
                if (!chosen) {
                    chosen = true
                    // 将整个卡片内容替换为"已确认"状态
                    showConfirmedState(card, opt.confirmedLabel, opt.primary.startsWith("拒绝"))
                    opt.action()
                }
            }
            optionList.add(row)
        }

        card.add(optionList, BorderLayout.CENTER)
        return card
    }

    // ---- 私有构建辅助方法 ----

    /**
     * 构建单个选项行。
     *
     * @param option    主文本
     * @param subText   副文本（可为 null）
     * @param isDefault 是否默认高亮（第一个选项）
     * @param onClick   点击回调
     */
    private fun buildOptionRow(
        option: String,
        subText: String?,
        isDefault: Boolean,
        onClick: () -> Unit
    ): JPanel {
        // 使用 AtomicBoolean 追踪 hover 状态（用于重绘）
        var hovered = isDefault

        val row = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                if (hovered) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    // hover / 默认高亮：toolBg 稍深（在 alpha 混合层之上再叠一层）
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
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // 左侧内容面板：X 轴水平排列 chevron + 文字区
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        // Chevron 符号（默认项展示 ❯，其他为空格占位）
        val chevron = JLabel(if (isDefault) "❯" else " ").apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = ChatTheme.toolFg
            preferredSize = Dimension(14, preferredSize.height)
            minimumSize = Dimension(14, minimumSize.height)
            maximumSize = Dimension(14, Int.MAX_VALUE)
            border = JBUI.Borders.empty(0, 2, 0, 6)
        }
        inner.add(chevron)

        // 文字区：主文本 + 副文本
        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val primaryLabel = JLabel(option).apply {
            font = ChatTheme.metaFont
            foreground = if (isDefault) ChatTheme.textPrimary else ChatTheme.textSecondary
            alignmentX = Component.LEFT_ALIGNMENT
        }
        textPanel.add(primaryLabel)

        if (!subText.isNullOrBlank()) {
            textPanel.add(Box.createRigidArea(Dimension(0, 2)))
            textPanel.add(JLabel(subText).apply {
                font = ChatTheme.metaFont
                foreground = ChatTheme.textMuted
                alignmentX = Component.LEFT_ALIGNMENT
            })
        }

        inner.add(textPanel)
        inner.add(Box.createHorizontalGlue())
        row.add(inner, BorderLayout.CENTER)

        // 鼠标事件：hover 高亮 + 点击
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                chevron.text = "❯"
                primaryLabel.foreground = ChatTheme.textPrimary
                row.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = isDefault  // 默认项保持高亮
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
     * 将选项列表替换为确认结果标签，保留头部和 diff 区。
     * 卡片不消失，可供用户回看授权记录。
     */
    private fun showConfirmedState(card: JPanel, label: String, isRejected: Boolean) {
        val centerComp = (card.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
        if (centerComp != null) card.remove(centerComp)

        val confirmedColor = when {
            isRejected -> ChatTheme.error
            label.contains("白名单") -> ChatTheme.doneCheck
            else -> ChatTheme.doneCheck
        }

        val confirmedRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 14, 6, 10)
        }
        confirmedRow.add(JLabel(label).apply {
            font = ChatTheme.metaFont.deriveFont(Font.BOLD)
            foreground = confirmedColor
        })
        confirmedRow.add(Box.createHorizontalGlue())

        card.add(confirmedRow, BorderLayout.CENTER)
        card.revalidate()
        card.repaint()
    }

    // ---- diff 预览块 ----

    /**
     * 构建行级 diff 预览面板。
     *
     * - 单色 codeBg 背景，等宽字体
     * - ADD 行前缀 "+ "，颜色 diffAddFg
     * - DEL 行前缀 "- "，颜色 diffDelFg
     * - CTX 行前缀 "  "，颜色 textMuted
     * - 超过 DIFF_PREVIEW_MAX_LINES 行时末尾显示省略提示
     */
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
            // 每行文字截断以避免超宽
            val text = (prefix + line.text).take(200)
            val label = JLabel(text).apply {
                font = ChatTheme.codeFont.deriveFont(ChatTheme.metaFont.size.toFloat())
                foreground = color
                alignmentX = Component.LEFT_ALIGNMENT
            }
            panel.add(label)
        }

        // 若有省略行，显示提示
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

    // ---- 卡片圆角边框 ----

    /**
     * 圆角边框：1px 实线，颜色由外部传入（toolBar 或 danger）。
     */
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
