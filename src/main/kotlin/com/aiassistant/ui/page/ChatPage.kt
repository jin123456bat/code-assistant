package com.aiassistant.ui.page

import com.aiassistant.agent.MultiAgentManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.EditorSelectionListener
import com.aiassistant.ui.chat.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionListener
import javax.swing.*

class ChatPage(
    project: Project,
    restoreSessionId: String? = null
) : JPanel(BorderLayout()) {

    private val viewModel = ChatViewModel(project, restoreSessionId)
    private lateinit var planCard: PlanCard
    private val messageContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = AppColors.pageBg
    }
    private var autoScroll = true
    private val toolCards = mutableMapOf<String, ToolCallCard>()
    private val scrollPane = JScrollPane(messageContainer).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        border = BorderFactory.createEmptyBorder(); background = AppColors.pageBg
        verticalScrollBar.addAdjustmentListener { e ->
            if (!e.valueIsAdjusting) {
                val bar = verticalScrollBar; autoScroll =
                    bar.value + bar.visibleAmount >= bar.maximum - 50
            }
        }
    }
    private var streamingBubble: JComponent? = null
    private val streamingBuf = StringBuilder()
    private var reasoningBubble: JPanel? = null
    private val reasoningBuf = StringBuilder()
    private var reasoningStartTime = 0L

    // ── MultiAgentBlock（子 Agent 进度展示）──
    private val multiAgentBlock = MultiAgentBlock(
        onSessionClick = null  // 子 session 详情跳转暂未实现
    ).apply { isVisible = false }
    private var multiAgentBlockIndex: Int = -1  // -1 = 未添加到 messageContainer

    init {
        planCard = PlanCard(
            onResume = { viewModel.sendMessage("继续执行计划") },
            onPause = {
                viewModel.session.plan?.status =
                    com.aiassistant.agent.PlanExecutor.Plan.Status.PAUSED
            },
            onRetry = { viewModel.sendMessage("重试当前计划步骤") },
            onSkip = { viewModel.sendMessage("跳过当前计划步骤") },
            onAbort = { viewModel.session.plan = null; planCard.isVisible = false }
        ).apply { isVisible = false }

        // 标题行：会话标题 + 清空按钮 + 关闭按钮，对齐 docs/ui/pages.md §十二 ChatPage 组件树
        // 初始显示当前会话标题（默认为"新会话"），标题生成后通过 onTitleChanged 回调更新
        val titleLabel = JLabel(viewModel.session.title).apply {
            font = font.deriveFont(13f).deriveFont(java.awt.Font.BOLD)
        }
        val clearButton = JButton("🗑").apply {
            toolTipText = "清空会话"
            font = font.deriveFont(14f)
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            addActionListener {
                viewModel.clearSession()
                messageContainer.removeAll()
                toolCards.clear()
                resetMultiAgentBlock()
                addTimestampMarker()
                messageContainer.revalidate()
                messageContainer.repaint()
            }
        }
        val closeButton = JButton("✕").apply {
            toolTipText = "关闭面板"
            font = font.deriveFont(14f)
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            addActionListener {
                val toolWindow =
                    ToolWindowManager.getInstance(project).getToolWindow("Code Assistant")
                toolWindow?.hide(null)
            }
        }
        val titleBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
            )
            add(titleLabel, BorderLayout.WEST)
            add(JPanel().apply {
                add(clearButton)
                add(closeButton)
            }, BorderLayout.EAST)
        }
        // 对齐 docs/ui/components.md §6 ChatPage 组件树：
        // NORTH=标题行+PlanCard, CENTER=JScrollPane→messageContainer
        val northPanel = JPanel(BorderLayout())
        northPanel.add(titleBar, BorderLayout.NORTH)
        northPanel.add(planCard, BorderLayout.SOUTH)
        add(northPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        val inputArea = ChatInputArea(
            onSend = { text ->
                viewModel.sendMessage(text)
                streamingBuf.clear(); streamingBubble = null
                reasoningBuf.clear(); reasoningBubble = null
            },
            onStop = { viewModel.cancel() },
            onNewSession = {
                viewModel.newSession()
                messageContainer.removeAll()
                toolCards.clear()
                resetMultiAgentBlock()
                reasoningBuf.clear(); reasoningBubble = null
                streamingBuf.clear(); streamingBubble = null
                addTimestampMarker()
                messageContainer.revalidate()
                messageContainer.repaint()
            },
            onInputChanged = { text ->
                viewModel.updateInputState(text = text)
            },
            onFillPreviousMessage = {
                // 对齐 docs/ui/pages.md §十 快捷键：↑（在空输入框）→ 填充上一条消息
                // 查找最近的未删除用户消息
                viewModel.messages
                    .filter { it.type == ChatMessage.Type.USER_TEXT && !it.deleted }
                    .lastOrNull()?.content
            }
        ).apply { setProject(project) }
        EditorSelectionListener(
            project,
            onSelectionChanged = { filePath, startLine, endLine, content ->
                inputArea.setSelectionReference(
                    fileName = filePath,
                    lineRange = "$startLine-$endLine",
                    content = content
                )
            },
            onSelectionCleared = { inputArea.clearSelectionReference() }
        )
        add(inputArea, BorderLayout.SOUTH)

        // 输入状态变化时更新 token 计数显示（对齐 docs/ui/chat.md §十二 InputState.tokenCount）
        viewModel.onInputStateChanged = { state ->
            inputArea.updateTokenCount(state.tokenCount)
        }

        viewModel.onMessageAdded = { msg ->
            streamingBubble?.let { messageContainer.remove(it); streamingBubble = null }
            // 对齐 docs/ui/components.md §3.2 Error 状态：发送失败时输入区域标红
            if (msg.type == ChatMessage.Type.ERROR) {
                inputArea.showError()
            }
            animateBubbleAppear(renderMessage(msg))
            messageContainer.add(Box.createVerticalStrut(8))
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onToolCallStarted = { toolUseId, toolName, params ->
            streamingBubble?.let { messageContainer.remove(it); streamingBubble = null }
            val paramsText = params.entries.joinToString(", ") { "${it.key}=${it.value}" }
            val card = ToolCallCard(toolName, paramsText, ToolCallCard.ToolCallState.PENDING)
            toolCards[toolUseId] = card
            animateBubbleAppear(card)
            messageContainer.add(Box.createVerticalStrut(8))
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            val uiState = runCatching { ToolCallCard.ToolCallState.valueOf(state.name) }
                .getOrDefault(ToolCallCard.ToolCallState.ERROR)
            toolCards[toolUseId]?.setState(uiState, result, durationMs)
            messageContainer.revalidate()
            messageContainer.repaint()
        }
        viewModel.onStreamingToken = { token ->
            streamingBuf.append(token)
            streamingBubble?.let { messageContainer.remove(it) }
            streamingBubble = ChatBubbleRenderer.renderStreaming(streamingBuf.toString())
            // 流式更新的第一个 token 使用动画，后续直接替换不加动画
            if (streamingBuf.length == token.length) {
                animateBubbleAppear(streamingBubble!!)
            } else {
                messageContainer.add(streamingBubble)
            }
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onReasoningContent = { reasoning ->
            // 思考过程累积后渲染为折叠块，对齐 docs/ui/chat.md §三
            if (reasoningBuf.isEmpty()) {
                reasoningStartTime = System.currentTimeMillis()
            }
            reasoningBuf.append(reasoning)
            reasoningBubble?.let { messageContainer.remove(it) }
            val durationMs = System.currentTimeMillis() - reasoningStartTime
            reasoningBubble = ChatBubbleRenderer.renderThinking(reasoningBuf.toString(), durationMs)
            // 思考过程块位于消息流末尾，没有 tool call 时在流式回复前，有 tool call 时在 tool call 前
            messageContainer.add(reasoningBubble)
            messageContainer.add(Box.createVerticalStrut(4))
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onStateChanged = {
            inputArea.setInputEnabled(!viewModel.isRunning)
            val plan = viewModel.session.plan
            planCard.isVisible =
                plan != null && plan.status == com.aiassistant.agent.PlanExecutor.Plan.Status.PAUSED
            if (planCard.isVisible && plan != null) {
                planCard.setPlan(plan.summary, plan.plans.map {
                    PlanCard.StepRow(it.id, it.description, "工具: ${it.tool}")
                })
            }
        }
        // 订阅子 Agent 事件，驱动 MultiAgentBlock UI
        viewModel.onSubAgentEvent = { event ->
            SwingUtilities.invokeLater { handleSubAgentEvent(event) }
        }

        // 订阅会话标题异步生成回调，对齐 docs/ui/pages.md §十二 ChatPage 标题行
        viewModel.onTitleChanged = { title ->
            SwingUtilities.invokeLater { titleLabel.text = title }
        }

        if (viewModel.messages.isEmpty()) {
            addTimestampMarker()
        } else {
            viewModel.messages.forEach { msg ->
                messageContainer.add(renderMessage(msg))
                messageContainer.add(Box.createVerticalStrut(8))
            }
            messageContainer.revalidate()
        }
    }

    /**
     * 确保 MultiAgentBlock 在 messageContainer 中可见。
     * 首个子 Agent 启动时插入消息流（位于流式气泡上方）。
     */
    private fun ensureMultiAgentBlockVisible() {
        if (multiAgentBlockIndex >= 0) return
        streamingBubble?.let { messageContainer.remove(it) }
        messageContainer.add(multiAgentBlock)
        multiAgentBlockIndex = messageContainer.componentCount - 1
        multiAgentBlock.isVisible = true
        streamingBubble?.let { messageContainer.add(it) }
        messageContainer.revalidate()
        messageContainer.repaint()
    }

    /** 将 SubAgentEvent 映射到 MultiAgentBlock API */
    private fun handleSubAgentEvent(event: MultiAgentManager.SubAgentEvent) {
        when (event) {
            is MultiAgentManager.SubAgentEvent.Started -> {
                ensureMultiAgentBlockVisible()
                multiAgentBlock.setConcurrency(
                    running = multiAgentBlock.getActiveSubAgentCount() + 1,
                    total = 3  // DEFAULT_MAX_CONCURRENT
                )
                val info = MultiAgentBlock.SubAgentInfo(
                    id = event.agentId,
                    name = "子 Agent",
                    task = event.task,
                    state = MultiAgentBlock.SubAgentState.RUNNING,
                    sessionId = event.subSessionId
                )
                multiAgentBlock.addSubAgent(info)
                multiAgentBlock.expand()
                messageContainer.revalidate()
                scrollToBottom()
            }

            is MultiAgentManager.SubAgentEvent.StreamToken -> {
                multiAgentBlock.appendSubAgentStream(event.agentId, event.token)
            }

            is MultiAgentManager.SubAgentEvent.ToolCallStarted -> {
                val paramsText = event.params.entries.joinToString(", ") { "${it.key}=${it.value}" }
                multiAgentBlock.addSubAgentToolCard(
                    event.agentId, event.toolUseId, event.toolName, paramsText
                )
            }

            is MultiAgentManager.SubAgentEvent.ToolCallStateChanged -> {
                val uiState = runCatching {
                    ToolCallCard.ToolCallState.valueOf(event.state.name)
                }.getOrDefault(ToolCallCard.ToolCallState.ERROR)
                multiAgentBlock.updateSubAgentToolCard(
                    event.agentId, event.toolUseId, uiState, event.result, event.durationMs
                )
            }

            is MultiAgentManager.SubAgentEvent.Completed -> {
                multiAgentBlock.setSubAgentState(event.agentId, MultiAgentBlock.SubAgentState.DONE)
                multiAgentBlock.setSubAgentFooter(
                    event.agentId,
                    event.durationMs,
                    event.subSessionId
                )
                messageContainer.revalidate()
                messageContainer.repaint()
            }

            is MultiAgentManager.SubAgentEvent.Failed -> {
                multiAgentBlock.setSubAgentState(event.agentId, MultiAgentBlock.SubAgentState.ERROR)
                multiAgentBlock.markSubAgentError(event.agentId, event.errorMessage)
                messageContainer.revalidate()
                messageContainer.repaint()
            }
        }
    }

    /** 重置 MultiAgentBlock 状态（clear/new/restore 时调用） */
    private fun resetMultiAgentBlock() {
        if (multiAgentBlockIndex >= 0) {
            messageContainer.remove(multiAgentBlock)
            multiAgentBlockIndex = -1
        }
        multiAgentBlock.clear()
        multiAgentBlock.isVisible = false
    }

    private fun addTimestampMarker() {
        val now = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        messageContainer.add(
            renderMessage(
                ChatMessage(
                    type = ChatMessage.Type.SYSTEM,
                    content = "── $now ──"
                )
            )
        )
        messageContainer.add(Box.createVerticalStrut(8)); messageContainer.revalidate()
    }

    private fun renderMessage(msg: ChatMessage): JComponent =
        ChatBubbleRenderer.render(
            msg,
            onRetry = if (viewModel.canRetry) ({ viewModel.retryLastTurn() }) else null,
            onFeedback = if (msg.type == ChatMessage.Type.AGENT_TEXT) ({ feedback ->
                viewModel.recordFeedback(msg.id, feedback)
            }) else null,
            panelWidth = scrollPane.viewport.width
        )

    /**
     * 根据面板宽度动态更新所有气泡的 setMaximumSize()。
     * 对齐 docs/ui/design-system.md §八：面板宽度变化 → 更新所有气泡的最大宽度。
     *
     * @param floating ToolWindow 是否处于浮动模式。浮动时无论实际宽度多大均使用 < 250px 档位（95%/95%）。
     */
    fun updateBubbleMaxWidths(floating: Boolean = false) {
        val panelWidth = scrollPane.viewport.width
        if (panelWidth <= 0) return

        val (userRatio, agentRatio) = if (floating) {
            0.95 to 0.95
        } else when {
            panelWidth > 500 -> 0.60 to 0.75
            panelWidth in 351..500 -> 0.70 to 0.85
            panelWidth in 250..350 -> 0.90 to 0.95
            else -> 0.95 to 0.95
        }
        val userMaxWidth = (panelWidth * userRatio).toInt()
        val agentMaxWidth = (panelWidth * agentRatio).toInt()

        for (component in messageContainer.components) {
            if (component !is JPanel) continue
            val bubbleType = component.getClientProperty("bubbleType") as? String ?: continue
            val maxWidth = when (bubbleType) {
                "user" -> userMaxWidth
                "agent", "error" -> agentMaxWidth
                else -> continue
            }
            component.maximumSize = java.awt.Dimension(maxWidth, Int.MAX_VALUE)
        }
        messageContainer.revalidate()
    }

    /**
     * 切换到已有会话（对齐 docs/ui/pages.md §二：CardLayout 不销毁隐藏页面，
     * ChatPage 复用同一实例，仅切换内部 session）。重置 UI 状态并重新渲染历史消息。
     */
    fun restoreSession(sessionId: String?) {
        planCard.isVisible = false
        messageContainer.removeAll()
        toolCards.clear()
        resetMultiAgentBlock()
        streamingBuf.clear()
        streamingBubble = null
        reasoningBuf.clear()
        reasoningBubble = null
        reasoningStartTime = 0L
        autoScroll = true
        viewModel.restoreSession(sessionId)
        addTimestampMarker()
        viewModel.messages.forEach { msg ->
            messageContainer.add(renderMessage(msg))
            messageContainer.add(Box.createVerticalStrut(8))
        }
        messageContainer.revalidate()
        messageContainer.repaint()
        scrollToBottom()
    }

    private fun scrollToBottom() {
        if (!autoScroll) return
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    /**
     * 检测系统是否启用了"减少动效"（prefers-reduced-motion）。
     * 对齐 docs/ui/design-system.md §七 prefers-reduced-motion 适配。
     */
    private fun isReducedMotionEnabled(): Boolean {
        val toolkit = Toolkit.getDefaultToolkit()
        val propertyNames = arrayOf(
            "awt.dynamicLayoutSupported",
            "apple.awt.reduceMotion"
        )
        for (name in propertyNames) {
            try {
                val prop = toolkit.getDesktopProperty(name)
                if (prop is Boolean && !prop) return true
            } catch (_: Exception) {
                // 忽略不支持的属性
            }
        }
        val osName = System.getProperty("os.name", "").lowercase()
        if (osName.contains("mac")) {
            try {
                val reduceMotion = toolkit.getDesktopProperty("awt.dynamicLayoutSupported")
                if (reduceMotion is Boolean && !reduceMotion) return true
            } catch (_: Exception) {
                // ignore
            }
        }
        return false
    }

    /**
     * 消息气泡出现动画：150ms ease-out，从下方 10px 滑入并淡入。
     * 对齐 docs/ui/design-system.md §七 动效：消息气泡出现 150ms ease-out。
     *
     * 实现方式：将 component 包裹在一个 AnimatedBubbleWrapper 中添加到消息列表，
     * wrapper 用 Swing Timer 在 150ms 内从 alpha=0 过渡到 alpha=1 的同时从 y+10 过渡到 y+0。
     * 动画完成后，wrapper 自动将 child 提升到父容器并移除自身，避免多余嵌套。
     * 如果系统启用了减少动效（prefers-reduced-motion），直接添加 component 不包裹动画。
     */
    private fun animateBubbleAppear(component: JComponent) {
        if (isReducedMotionEnabled()) {
            messageContainer.add(component)
            return
        }
        val wrapper = AnimatedBubbleWrapper(component)
        messageContainer.add(wrapper)
        wrapper.startAnimation()
    }

    /**
     * 消息气泡出现动画包装器。
     * 150ms ease-out：alpha 0→1，translateY 10→0（从下方滑入）。
     */
    private inner class AnimatedBubbleWrapper(private val child: JComponent) :
        JPanel(BorderLayout()) {
        private var alpha = 0.0f
        private var translateY = 10
        private val timer: Timer
        private var elapsed = 0
        private val durationMs = 150
        private val frameMs = 10 // ~100fps

        init {
            isOpaque = false
            add(child, BorderLayout.CENTER)
            this.minimumSize = child.minimumSize
            this.preferredSize = child.preferredSize
            this.maximumSize = child.maximumSize
            // 传递 bubbleType client property 给 wrapper，以便 updateBubbleMaxWidths 能识别
            val bubbleType = child.getClientProperty("bubbleType") as? String
            if (bubbleType != null) {
                putClientProperty("bubbleType", bubbleType)
            }
            timer = Timer(frameMs, null)
            timer.addActionListener(ActionListener {
                elapsed += frameMs
                val progress = (elapsed.toFloat() / durationMs).coerceAtMost(1.0f)
                // ease-out: t => 1 - (1-t)^2
                val eased = 1.0f - (1.0f - progress) * (1.0f - progress)
                alpha = eased
                translateY = (10 * (1.0f - eased)).toInt()
                revalidate()
                repaint()
                if (elapsed >= durationMs) {
                    alpha = 1.0f
                    translateY = 0
                    timer.stop()
                    // 动画完成后把 child 提升到父容器，移除 wrapper 避免多余嵌套
                    val parent = parent
                    if (parent is JComponent) {
                        val index = parent.getComponentZOrder(this@AnimatedBubbleWrapper)
                        parent.remove(this@AnimatedBubbleWrapper)
                        parent.add(child, index)
                        parent.revalidate()
                        parent.repaint()
                    }
                }
            })
            timer.isRepeats = true
        }

        fun startAnimation() {
            timer.start()
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2d.translate(0, translateY)
            super.paintComponent(g2d)
            g2d.dispose()
        }

        override fun doLayout() {
            if (child.parent == this) {
                child.setBounds(0, 0, width, height)
            }
        }

        override fun getPreferredSize(): java.awt.Dimension = child.preferredSize
        override fun getMinimumSize(): java.awt.Dimension = child.minimumSize
        override fun getMaximumSize(): java.awt.Dimension = child.maximumSize
    }
}
