package com.aiassistant.ui

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.text.View
import kotlin.math.ceil

/**
 * 自测量聊天气泡（替代旧的「构造期 fitWidth + lockRowHeight 冻结尺寸」方案）。
 *
 * 旧方案的根本缺陷：在构造时测量一次内容尺寸，然后把固定的
 * preferredSize/maximumSize 冻结上去。这要求「测量时刻 == 渲染时刻」，
 * 但真实 IDE 下 viewport 宽度、JBFont、HiDPI 缩放在构造时都未最终确定，
 * 导致：① 冻结的 maximumSize.height 偏小 → 文字被纵向裁切；
 *        ② 测量被分类成满宽 → AI 气泡填满整行、无法靠左。
 *
 * 本组件改为「实时自测量」：在 [getPreferredSize] / [getMaximumSize] 中
 * 按 **当前** 可用宽度即时计算。Swing 每次布局都会调用这两个方法，
 * 那一刻 viewport 宽度一定是真实值，从结构上消除时序依赖：
 *  - getMaximumSize().width = hug 宽 → 气泡贴合内容、不被横向撑满
 *    → 外层 X_AXIS BoxLayout 的 glue 把它推到对应一侧（结构性对齐保证）。
 *  - getMaximumSize().height = preferred 高 → 不被纵向拉伸成空盒。
 *  - getMinimumSize() = preferred → BoxLayout 不会压缩它 → 不裁字。
 *  - 高度用 getPreferredSpan(Y_AXIS)（诚实渲染高），而非 getMinimumSpan。
 *
 * @param content        气泡内容组件（用户：HTML JTextPane；AI：markdown 容器）。
 * @param bg             气泡背景色。
 * @param borderColor    描边色，null 表示无描边。
 * @param widthFraction  内容最大宽度占可用宽度的比例（用户 0.8 / AI 1.0）。
 * @param availableWidth 实时返回当前可用宽度（通常为 scrollPane.viewport.width）。
 */
class ChatBubble(
    val content: JComponent,
    private val bg: Color,
    private val borderColor: Color?,
    private val widthFraction: Double,
    private val availableWidth: () -> Int
) : JPanel(BorderLayout()) {

    /** token 消耗标签：默认隐藏，鼠标悬停时浮现（半透明极小字） */
    private var tokenLabel: JLabel? = null

    /** 鼠标悬停隐藏 token 标签的防抖 Timer，removeNotify 时停止防止泄漏 */
    private val hideTimer = javax.swing.Timer(200) { tokenLabel?.isVisible = false }.apply {
        isRepeats = false
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(ChatTheme.PAD_BUBBLE_V, ChatTheme.PAD_BUBBLE_H)
        add(content, BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hideTimer.stop()
                tokenLabel?.isVisible = true
            }
            override fun mouseExited(e: MouseEvent) {
                hideTimer.stop()
                hideTimer.start()  // 复用同一个 Timer，不每次创建新实例
            }
        })
    }

    override fun removeNotify() {
        super.removeNotify()
        hideTimer.stop()
    }

    /** 设置 token 消耗标签（半透明极小字，默认隐藏） */
    fun setTokenUsage(inputTokens: Int, outputTokens: Int) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        tokenLabel = JLabel(buildTokenText(inputTokens, outputTokens)).apply {
            font = ChatTheme.metaFont.deriveFont(8f)
            foreground = Color(textMutedColor.red, textMutedColor.green, textMutedColor.blue, 128)
            border = JBUI.Borders.empty(0, 0, 2, 4)
            isVisible = false
        }
        val south = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false; add(tokenLabel) }
        add(south, BorderLayout.SOUTH)
    }

    private fun buildTokenText(inputTokens: Int, outputTokens: Int): String {
        val parts = mutableListOf<String>()
        if (inputTokens > 0) parts.add("←${formatTokens(inputTokens)}")
        if (outputTokens > 0) parts.add("→${formatTokens(outputTokens)}")
        return parts.joinToString(" ")
    }

    private fun horizontalPad(): Int = JBUI.scale(ChatTheme.PAD_BUBBLE_H) * 2
    private fun verticalPad(): Int = JBUI.scale(ChatTheme.PAD_BUBBLE_V) * 2

    /** 当前内容区可用最大宽度（像素），随 viewport 实时变化。 */
    private fun contentWidthBudget(): Int {
        val avail = availableWidth()
        if (avail <= 10) {
            // viewport 尚未就绪：保守兜底（宁可偏窄，绝不超窗被裁）。
            // 待 viewport 就绪后一次 revalidate 即自我修正——本组件无冻结值。
            return maxOf(JBUI.scale(280) - horizontalPad(), JBUI.scale(40))
        }
        // AI (fraction >= 1.0)：全宽，不设 ABS_CAP 上限。
        // 用户 (fraction < 1.0)：按比例，受 ABS_CAP 兜底限制。
        val cap = if (widthFraction >= 1.0) {
            avail
        } else {
            minOf(JBUI.scale(ChatTheme.ABS_CAP), (avail * widthFraction).toInt())
        }
        return maxOf(cap - horizontalPad(), JBUI.scale(40))
    }

    override fun getPreferredSize(): Dimension {
        val budget = contentWidthBudget()
        val (w, h) = try {
            measure(content, budget)
        } catch (_: Exception) {
            budget to JBUI.scale(24)
        }
        return Dimension(w + horizontalPad(), h + verticalPad())
    }

    // hug：最大尺寸 == 首选尺寸，既不横向撑满（→ 靠边对齐）也不纵向拉伸（→ 无空盒）。
    override fun getMaximumSize(): Dimension = preferredSize

    // 最小尺寸 == 首选尺寸：BoxLayout 不会把气泡压扁 → 文字不被裁切。
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = ChatTheme.RADIUS * 2
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, arc, arc)
        if (borderColor != null) {
            g2.color = borderColor
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g2.dispose()
        super.paintComponent(g)
    }

    companion object {
        private val textMutedColor = ChatTheme.textMuted

        fun formatTokens(n: Int): String = when {
            n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
            n >= 1_000 -> "${n / 1_000}.${(n % 1_000) / 100}k"
            else -> "$n"
        }

        /**
         * 实时测量内容在给定最大宽度下的真实 (宽, 高)，并 hug content。
         * 不缓存、不冻结——每次布局即时计算，因此 viewport/字体就绪后自动正确。
         */
        fun measure(content: JComponent, budget: Int): Pair<Int, Int> = when (content) {
            is JTextPane -> measureTextPane(content, budget)
            is JTextArea -> measureTextArea(content, budget)
            is JPanel -> measurePanel(content, budget)
            else -> {
                content.size = Dimension(budget, Short.MAX_VALUE.toInt())
                val p = content.preferredSize
                minOf(p.width, budget) to p.height
            }
        }

        /** 用 HTML 根视图测 JTextPane：先量自然宽 hug，再按确定宽度量诚实高度。 */
        private fun measureTextPane(pane: JTextPane, budget: Int): Pair<Int, Int> {
            pane.setSize(budget, Short.MAX_VALUE.toInt())
            val root = pane.ui.getRootView(pane) ?: return budget to JBUI.scale(24)
            // 1) 自然首选宽 → hug（封顶 budget）
            root.setSize(budget.toFloat(), Short.MAX_VALUE.toFloat())
            val naturalW = ceil(root.getPreferredSpan(View.X_AXIS).toDouble()).toInt() + JBUI.scale(2)
            val w = maxOf(minOf(naturalW, budget), JBUI.scale(40))
            // 2) 用确定宽度重新量高度，避免单行/换行下高度被低估导致纵向裁字。
            //    关键：用 getPreferredSpan（诚实渲染高），而非 getMinimumSpan（会偏小→裁字）。
            root.setSize(w.toFloat(), Short.MAX_VALUE.toFloat())
            val h = ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + JBUI.scale(4)
            return w to maxOf(h, JBUI.scale(20))
        }

        /** JTextArea：lineWrap=false 量自然宽，超限则换行并按上限重量高度。测量结束后恢复原始状态。 */
        private fun measureTextArea(area: JTextArea, budget: Int): Pair<Int, Int> {
            val origLineWrap = area.lineWrap
            val origWrapStyleWord = area.wrapStyleWord
            area.lineWrap = false
            area.wrapStyleWord = false
            try {
                area.setSize(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val naturalW = area.preferredSize.width
                val w = if (naturalW <= budget) maxOf(naturalW, JBUI.scale(12)) else budget
                if (naturalW > budget) {
                    area.lineWrap = true
                    area.wrapStyleWord = true
                }
                area.setSize(w, Short.MAX_VALUE.toInt())
                return w to area.preferredSize.height
            } finally {
                area.lineWrap = origLineWrap
                area.wrapStyleWord = origWrapStyleWord
            }
        }

        /** markdown 容器：含代码块等非文本子节点 → 满宽；纯文本 → 按内部文本 hug。 */
        private fun measurePanel(panel: JPanel, budget: Int): Pair<Int, Int> {
            val hasNonText = panel.components.any {
                it is JComponent && it !is JTextPane && it !is JTextArea
            }
            val targetW = if (hasNonText) {
                budget
            } else {
                val inner = BubbleFactory.findFirstTextPane(panel)
                if (inner != null) measureTextPane(inner, budget).first else budget
            }
            // 以确定宽度排版后读真实高度（子组件换行高度依赖其被分配的宽度）。
            panel.setSize(targetW, Short.MAX_VALUE.toInt())
            panel.doLayout()
            // +2px 缓冲防止流式过程中 HTML 换行高度被低估导致最后一两行被裁切
            val h = panel.preferredSize.height + JBUI.scale(2)
            return targetW to maxOf(h, JBUI.scale(20))
        }
    }
}
