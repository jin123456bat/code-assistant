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
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
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
    ): JPanel = createAgentBubbleHandle(msg, streaming = false, panelWidth).component

    class AgentBubbleHandle internal constructor(
        val component: JPanel,
        internal val card: JPanel,
        internal val markdownBody: MarkdownBodyState,
        internal val timestampHost: JPanel,
        internal val timestampLabel: JLabel,
        internal val cursor: JLabel,
        internal val blinkTimer: Timer,
        internal var message: ChatMessage,
        internal var streaming: Boolean,
        internal var widthBudget: Int
    ) {
        fun appendStreaming(delta: String) {
            updateStreaming(message.content + delta)
        }

        fun updateStreaming(markdownText: String) {
            message = message.copy(content = markdownText)
            streaming = true
            ChatBubbleRenderer.updateAgentContent(this)
            ChatBubbleRenderer.updateAgentState(this)
            ChatBubbleRenderer.applyAgentLayout(this)
        }

        fun finish(message: ChatMessage) {
            require(message.type == ChatMessage.Type.AGENT_TEXT) {
                "AgentBubbleHandle can only finish an AGENT_TEXT message"
            }
            this.message = message
            streaming = false
            ChatBubbleRenderer.updateAgentContent(this)
            ChatBubbleRenderer.updateAgentState(this)
            ChatBubbleRenderer.applyAgentLayout(this)
        }

        fun constrainWidth(maxWidth: Int) {
            val normalized = maxWidth.coerceAtLeast(80)
            if (widthBudget == normalized) return
            widthBudget = normalized
            markdownBody.applyWidth(normalized)
            ChatBubbleRenderer.applyAgentLayout(this)
        }

        fun dispose() {
            blinkTimer.stop()
        }
    }

    internal class MarkdownBodyState internal constructor(
        val component: JPanel
    ) {
        private val views = mutableListOf<MarkdownUnitView>()
        private var widthBudget = 0

        fun update(markdown: String, panelWidth: Int) {
            val nextUnits = groupMarkdownBlocks(parseMarkdown(markdown))
            val widthChanged = panelWidth > 0 && widthBudget != panelWidth
            if (widthChanged) widthBudget = panelWidth
            var stablePrefix = 0
            while (stablePrefix < views.size && stablePrefix < nextUnits.size &&
                views[stablePrefix].canUpdate(nextUnits[stablePrefix])
            ) {
                views[stablePrefix].update(nextUnits[stablePrefix])
                stablePrefix++
            }

            if (stablePrefix < views.size) {
                for (index in views.lastIndex downTo stablePrefix) {
                    component.remove(views[index].component)
                }
                views.subList(stablePrefix, views.size).clear()
            }
            for (index in stablePrefix until nextUnits.size) {
                val view = createMarkdownUnitView(nextUnits[index], contentWidth(), widthBudget)
                views.add(view)
                component.add(view.component)
            }

            if (widthChanged) {
                views.take(stablePrefix).forEach { it.applyWidth(contentWidth(), widthBudget) }
            }
            component.revalidate()
            component.repaint()
        }

        fun applyWidth(panelWidth: Int) {
            if (widthBudget == panelWidth) return
            widthBudget = panelWidth
            views.forEach { it.applyWidth(contentWidth(), widthBudget) }
            component.revalidate()
        }

        private fun contentWidth(): Int =
            if (widthBudget > 0) (widthBudget - 30).coerceAtLeast(80) else 0
    }

    private sealed interface MarkdownRenderUnit {
        data class Single(val block: MarkdownBlock) : MarkdownRenderUnit
        data class CodeRun(val blocks: List<MarkdownBlock.CodeBlock>) : MarkdownRenderUnit
    }

    private fun groupMarkdownBlocks(blocks: List<MarkdownBlock>): List<MarkdownRenderUnit> =
        buildList {
            var index = 0
            while (index < blocks.size) {
                if (blocks[index] is MarkdownBlock.CodeBlock) {
                    val codes = mutableListOf<MarkdownBlock.CodeBlock>()
                    while (index < blocks.size && blocks[index] is MarkdownBlock.CodeBlock) {
                        codes.add(blocks[index] as MarkdownBlock.CodeBlock)
                        index++
                    }
                    add(MarkdownRenderUnit.CodeRun(codes))
                } else {
                    add(MarkdownRenderUnit.Single(blocks[index]))
                    index++
                }
            }
        }

    private sealed interface MarkdownUnitView {
        val component: JComponent
        fun canUpdate(unit: MarkdownRenderUnit): Boolean
        fun update(unit: MarkdownRenderUnit)
        fun applyWidth(contentWidth: Int, panelWidth: Int)
    }

    private class SingleMarkdownUnitView(
        private val view: MarkdownBlockView
    ) : MarkdownUnitView {
        override val component: JComponent get() = view.component
        override fun canUpdate(unit: MarkdownRenderUnit): Boolean =
            unit is MarkdownRenderUnit.Single && view.canUpdate(unit.block)

        override fun update(unit: MarkdownRenderUnit) {
            view.update((unit as MarkdownRenderUnit.Single).block)
        }

        override fun applyWidth(contentWidth: Int, panelWidth: Int) {
            view.applyWidth(contentWidth)
        }
    }

    private class CodeRunMarkdownUnitView(
        unit: MarkdownRenderUnit.CodeRun,
        contentWidth: Int,
        panelWidth: Int
    ) : MarkdownUnitView {
        override val component = JPanel().apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        private val codeViews = mutableListOf<MarkdownBlockView>()
        private var wide = panelWidth > 500
        private var currentContentWidth = contentWidth

        init {
            update(unit)
            applyWidth(contentWidth, panelWidth)
        }

        override fun canUpdate(unit: MarkdownRenderUnit): Boolean =
            unit is MarkdownRenderUnit.CodeRun

        override fun update(unit: MarkdownRenderUnit) {
            val codes = (unit as MarkdownRenderUnit.CodeRun).blocks
            val common = minOf(codeViews.size, codes.size)
            for (index in 0 until common) codeViews[index].update(codes[index])
            if (codes.size < codeViews.size) {
                for (index in codeViews.lastIndex downTo codes.size) {
                    component.remove(codeViews[index].component)
                }
                codeViews.subList(codes.size, codeViews.size).clear()
            }
            for (index in common until codes.size) {
                val view = createMarkdownBlockView(codes[index]).also {
                    it.applyWidth(currentContentWidth)
                }
                codeViews.add(view)
                component.add(view.component)
            }
            updateLayout()
        }

        override fun applyWidth(contentWidth: Int, panelWidth: Int) {
            currentContentWidth = contentWidth
            codeViews.forEach { it.applyWidth(contentWidth) }
            val nextWide = panelWidth > 500
            if (wide != nextWide) {
                wide = nextWide
                updateLayout()
            }
        }

        private fun updateLayout() {
            val count = codeViews.size.coerceAtLeast(1)
            component.layout = if (wide && count >= 2) {
                java.awt.GridLayout(1, count, 8, 0)
            } else {
                java.awt.GridLayout(count, 1, 0, if (count >= 2) 8 else 0)
            }
            component.revalidate()
        }
    }

    private fun createMarkdownUnitView(
        unit: MarkdownRenderUnit,
        contentWidth: Int,
        panelWidth: Int
    ): MarkdownUnitView = when (unit) {
        is MarkdownRenderUnit.Single ->
            SingleMarkdownUnitView(createMarkdownBlockView(unit.block).also { it.applyWidth(contentWidth) })

        is MarkdownRenderUnit.CodeRun ->
            CodeRunMarkdownUnitView(unit, contentWidth, panelWidth)
    }

    private class MarkdownBlockView(
        var block: MarkdownBlock,
        val component: JComponent,
        private val updateContent: (MarkdownBlock, Int) -> Unit,
        private val updateWidth: (Int) -> Unit = {}
    ) {
        private var width = 0
        private var initialized = false

        fun canUpdate(next: MarkdownBlock): Boolean = block::class == next::class

        fun update(next: MarkdownBlock) {
            if (block == next && initialized) return
            block = next
            updateContent(next, width)
            initialized = true
        }

        fun applyWidth(newWidth: Int) {
            val contentNeedsRefresh = !initialized || block !is MarkdownBlock.CodeBlock
            width = newWidth
            updateWidth(newWidth)
            if (contentNeedsRefresh) {
                updateContent(block, newWidth)
                initialized = true
            }
        }
    }

    fun createStreamingAgentBubble(markdownText: String = ""): AgentBubbleHandle =
        createAgentBubbleHandle(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = markdownText),
            streaming = true,
            panelWidth = 0
        )

    fun constrainWidth(component: Component, maxWidth: Int): Boolean {
        findAgentBubbleHandle(component)?.let {
            it.constrainWidth(maxWidth)
            return true
        }
        findThinkingHandle(component)?.let {
            it.constrainWidth(maxWidth)
            return true
        }
        return false
    }

    private fun findAgentBubbleHandle(component: Component): AgentBubbleHandle? {
        if (component is JComponent) {
            val handle = component.getClientProperty("agentBubbleHandle") as? AgentBubbleHandle
            if (handle != null) return handle
        }
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                findAgentBubbleHandle(child)?.let { return it }
            }
        }
        return null
    }

    private fun findThinkingHandle(component: Component): ThinkingHandle? {
        if (component is JComponent) {
            val handle = component.getClientProperty("thinkingHandle") as? ThinkingHandle
            if (handle != null) return handle
        }
        if (component is java.awt.Container) {
            component.components.forEach { child ->
                findThinkingHandle(child)?.let { return it }
            }
        }
        return null
    }

    private fun createAgentBubbleHandle(
        msg: ChatMessage,
        streaming: Boolean,
        panelWidth: Int
    ): AgentBubbleHandle {
        val outer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            putClientProperty("bubbleType", "agent")
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = AppColors.cardBg
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                RoundedBorder(12, AppColors.border),
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.primary),
                    BorderFactory.createEmptyBorder(12, 12, 12, 12)
                )
            )
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val timestampHost = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val timestampLabel = JLabel().apply {
            foreground = AppColors.textSecondary
            font = font.deriveFont(10f)
        }
        val cursor = JLabel("▍").apply {
            foreground = AppColors.primary
            font = font.deriveFont(13f)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val blinkTimer = Timer(500) { cursor.isVisible = !cursor.isVisible }.apply {
            isRepeats = true
        }
        card.add(body)
        card.add(cursor)
        timestampHost.add(timestampLabel, BorderLayout.WEST)
        content.add(card)
        content.add(timestampHost)
        outer.add(content)

        val handle = AgentBubbleHandle(
            component = outer,
            card = card,
            markdownBody = MarkdownBodyState(body),
            timestampHost = timestampHost,
            timestampLabel = timestampLabel,
            cursor = cursor,
            blinkTimer = blinkTimer,
            message = msg,
            streaming = streaming,
            widthBudget = 0
        )
        outer.putClientProperty("agentBubbleHandle", handle)
        outer.addHierarchyListener {
            if (it.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L &&
                !outer.isDisplayable
            ) {
                handle.dispose()
            }
        }
        updateAgentContent(handle)
        updateAgentState(handle)
        if (panelWidth > 0) handle.constrainWidth(panelWidth) else applyAgentLayout(handle)
        return handle
    }

    private fun updateAgentContent(handle: AgentBubbleHandle) {
        handle.markdownBody.update(handle.message.content, handle.widthBudget)
        handle.card.accessibleContext.accessibleDescription =
            "Agent 消息: ${handle.message.content.take(100)}"
    }

    private fun updateAgentState(handle: AgentBubbleHandle) {
        if (handle.streaming) {
            handle.cursor.isVisible = true
            handle.timestampHost.isVisible = false
            if (!handle.blinkTimer.isRunning) handle.blinkTimer.start()
        } else {
            handle.blinkTimer.stop()
            handle.cursor.isVisible = false
            val tokenInfo = handle.message.tokenDelta?.let {
                "↑${it.input / 1000}K ↓${it.output / 1000}K"
            }.orEmpty()
            handle.timestampLabel.text = timestampText(handle.message, tokenInfo)
            handle.timestampHost.isVisible = true
        }
    }

    private fun applyAgentLayout(handle: AgentBubbleHandle) {
        handle.card.preferredSize = null
        if (handle.widthBudget > 0) {
            val preferredHeight = handle.card.preferredSize.height
            if (handle.card.preferredSize.width > handle.widthBudget) {
                handle.card.preferredSize = Dimension(handle.widthBudget, preferredHeight)
            }
            handle.card.maximumSize =
                Dimension(handle.card.preferredSize.width.coerceAtMost(handle.widthBudget), handle.card.preferredSize.height)
        } else {
            handle.card.maximumSize = handle.card.preferredSize
        }
        refreshCappedHeights(handle.component)
        handle.component.maximumSize = Dimension(Int.MAX_VALUE, handle.component.preferredSize.height)
        handle.markdownBody.component.revalidate()
        handle.card.revalidate()
        handle.component.revalidate()
        handle.component.repaint()
    }

    private fun createMarkdownBlockView(block: MarkdownBlock): MarkdownBlockView =
        when (block) {
            is MarkdownBlock.Paragraph -> {
                val label = wrappingLabel("", 0).apply {
                    font = font.deriveFont(12f)
                    border = BorderFactory.createEmptyBorder(1, 0, 1, 0)
                }
                MarkdownBlockView(block, leftAlignedRow(label), { next, width ->
                    val paragraph = next as MarkdownBlock.Paragraph
                    val html = escapeHtml(paragraph.text)
                        .replace(Regex("`([^`]+)`")) { "<code>${it.groupValues[1]}</code>" }
                        .replace("\n", "<br>")
                    updateWrappingLabel(label, html, width)
                })
            }

            is MarkdownBlock.Header -> {
                val label = wrappingLabel("", 0)
                MarkdownBlockView(block, leftAlignedRow(label), { next, width ->
                    val header = next as MarkdownBlock.Header
                    updateWrappingLabel(
                        label,
                        "<b style='font-size:14px'>${escapeHtml(header.text)}</b>",
                        width
                    )
                })
            }

            is MarkdownBlock.ListItem -> {
                val label = wrappingLabel("", 0)
                MarkdownBlockView(block, leftAlignedRow(label), { next, width ->
                    val item = next as MarkdownBlock.ListItem
                    updateWrappingLabel(label, "&nbsp;&nbsp;• ${escapeHtml(item.text)}", width)
                })
            }

            is MarkdownBlock.QuoteBlock -> {
                val area = JTextArea().apply {
                    font = Font(Font.SANS_SERIF, Font.ITALIC, 12)
                    foreground = AppColors.textSecondary
                    background = AppColors.quoteBg
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 3, 0, 0, AppColors.quoteBorder),
                        BorderFactory.createEmptyBorder(4, 8, 4, 8)
                    )
                }
                MarkdownBlockView(block, area, { next, width ->
                    area.text = (next as MarkdownBlock.QuoteBlock).text
                    if (width > 0) area.setSize(width, Int.MAX_VALUE)
                })
            }

            is MarkdownBlock.CodeBlock -> {
                val code = createHighlightedCodePane("").apply {
                    border = BorderFactory.createCompoundBorder(
                        RoundedBorder(8, AppColors.codeBorder),
                        BorderFactory.createEmptyBorder(8, 10, 8, 10)
                    )
                }
                val scrollPane = JScrollPane(code).apply {
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                    border = BorderFactory.createEmptyBorder()
                    minimumSize = Dimension(80, minimumSize.height)
                }
                MarkdownBlockView(block, scrollPane, { next, _ ->
                    updateHighlightedCodePane(code, (next as MarkdownBlock.CodeBlock).code)
                })
            }
        }

    private fun updateWrappingLabel(label: JLabel, html: String, width: Int) {
        label.putClientProperty("wrapHtml", html)
        label.text = htmlWithWidth(html, width)
        label.maximumSize = Dimension(label.preferredSize.width, label.preferredSize.height)
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
            maximumSize = Dimension(preferredSize.width, preferredSize.height)
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
        // Swing HTML 渲染器对 <body> 有默认 margin:8px，用 marginwidth=0 消除
        if (width > 0) {
            "<html><body width='${width.coerceAtLeast(80)}' align='left' marginwidth='0' marginheight='0'>$html</body></html>"
        } else {
            "<html><body align='left' marginwidth='0' marginheight='0'>$html</body></html>"
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

    private fun renderTimestamp(msg: ChatMessage, extra: String = "", leftAlign: Boolean = false): JPanel {
        val constraint = if (leftAlign) BorderLayout.WEST else BorderLayout.EAST
        return JPanel(BorderLayout()).apply {
            add(JLabel(timestampText(msg, extra)).apply {
                foreground = AppColors.textSecondary; font = font.deriveFont(10f)
            }, constraint)
        }
    }

    private fun timestampText(msg: ChatMessage, extra: String = ""): String {
        val timestamp = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(msg.timestamp)
        return if (extra.isNotEmpty()) "$timestamp | $extra" else timestamp
    }

    /**
     * 流式 Markdown 渲染。
     *
     * 对齐 docs/ui/chat.md §二 "流式气泡"：末尾闪烁光标 ▍ (#3B82F6, 500ms blink)。
     */
    fun renderStreaming(markdownText: String): JComponent =
        createStreamingAgentBubble(markdownText).component

    /**
     * 渲染思考过程折叠块（对齐 docs/ui/chat.md §三）。
     * 默认折叠，">" 箭头可点击展开/折叠，显示完整思考内容。
     */
    fun renderThinking(reasoning: String, durationMs: Long): JPanel =
        createThinkingHandle(reasoning, durationMs).component

    class ThinkingHandle internal constructor(
        val component: JPanel,
        internal val block: JPanel,
        internal val body: JTextArea,
        internal val bodyScroll: JScrollPane,
        internal val durationLabel: JLabel,
        internal var widthBudget: Int = 0
    ) {
        fun update(reasoning: String, durationMs: Long) {
            body.text = reasoning
            refresh(durationMs)
        }

        fun append(delta: String, durationMs: Long) {
            body.append(delta)
            refresh(durationMs)
        }

        fun constrainWidth(maxWidth: Int) {
            val normalized = maxWidth.coerceAtLeast(80)
            if (widthBudget == normalized) return
            widthBudget = normalized
            refreshLayout()
        }

        private fun refresh(durationMs: Long) {
            durationLabel.text = formatThinkingDuration(durationMs)
            refreshLayout()
        }

        private fun refreshLayout() {
            block.preferredSize = null
            component.preferredSize = null
            if (bodyScroll.isVisible) {
                val currentWidth = listOf(widthBudget, component.width, block.width, component.preferredSize.width)
                    .firstOrNull { it > 0 } ?: 0
                if (currentWidth > 0) {
                    updateThinkingBodySize(body, bodyScroll, currentWidth)
                    block.preferredSize = Dimension(currentWidth, block.preferredSize.height)
                }
            }
            val preferredHeight = component.preferredSize.height
            if (widthBudget > 0) {
                block.preferredSize = Dimension(widthBudget, block.preferredSize.height)
                component.preferredSize = Dimension(widthBudget, preferredHeight)
            }
            component.maximumSize = Dimension(
                if (widthBudget > 0) widthBudget else Int.MAX_VALUE,
                component.preferredSize.height
            )
            block.revalidate()
            component.revalidate()
            component.repaint()
        }
    }

    fun createThinkingHandle(reasoning: String, durationMs: Long): ThinkingHandle {
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
        val durationLabel = JLabel(formatThinkingDuration(durationMs)).apply {
            foreground = AppColors.thinkingTimeFg
            font = font.deriveFont(10f)
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
            add(durationLabel, BorderLayout.EAST)
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
            // ponytail: 内层滚到头时转发给外层，避免嵌套滚动卡死
            addMouseWheelListener { e ->
                val bar = verticalScrollBar
                val atBottom = e.wheelRotation > 0 && bar.value + bar.visibleAmount >= bar.maximum
                val atTop = e.wheelRotation < 0 && bar.value <= bar.minimum
                if (atBottom || atTop) {
                    var parent: java.awt.Container? = this.parent
                    while (parent != null) {
                        if (parent is JScrollPane) {
                            parent.dispatchEvent(
                                javax.swing.SwingUtilities.convertMouseEvent(this, e, parent)
                            )
                            break
                        }
                        parent = parent.parent
                    }
                }
            }
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
        capRowHeight(outer)
        return ThinkingHandle(outer, block, body, bodyScroll, durationLabel).also {
            outer.putClientProperty("thinkingHandle", it)
        }
    }

    private fun formatThinkingDuration(durationMs: Long): String =
        "${durationMs / 1000}.${(durationMs % 1000) / 100}s"

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
        updateHighlightedCodePane(pane, code)
        return pane
    }

    private fun updateHighlightedCodePane(pane: JTextPane, code: String) {
        val doc = pane.styledDocument
        val def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE)
        val defaultStyle = (doc.getStyle("code") ?: doc.addStyle("code", def)).apply {
            StyleConstants.setForeground(this, AppColors.textSecondary)
        }
        val kwStyle = (doc.getStyle("kw") ?: doc.addStyle("kw", defaultStyle)).apply {
            StyleConstants.setForeground(this, Color(0xCF222E))
            StyleConstants.setBold(this, true)
        }
        val strStyle = (doc.getStyle("str") ?: doc.addStyle("str", defaultStyle)).apply {
            StyleConstants.setForeground(this, Color(0x0A3069))
        }
        val cmStyle = (doc.getStyle("cm") ?: doc.addStyle("cm", defaultStyle)).apply {
            StyleConstants.setForeground(this, Color(0x6E7781))
            StyleConstants.setItalic(this, true)
        }
        val fnStyle = (doc.getStyle("fn") ?: doc.addStyle("fn", defaultStyle)).apply {
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
            doc.remove(0, doc.length)
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
