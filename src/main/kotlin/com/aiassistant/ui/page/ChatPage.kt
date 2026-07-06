package com.aiassistant.ui.page

import com.aiassistant.agent.MultiAgentManager
import com.aiassistant.ui.AppColors
import com.aiassistant.ui.AppAnimations
import com.aiassistant.ui.Banner
import com.aiassistant.ui.EditorSelectionListener
import com.aiassistant.ui.MessageBus
import com.aiassistant.ui.chat.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionListener
import javax.swing.*

class ChatPage(
    project: Project,
    restoreSessionId: String? = null,
    private val enableIdeServices: Boolean = true
) : JPanel(BorderLayout()), Disposable {

    private val viewModel = ChatViewModel(project, restoreSessionId)
    private lateinit var planCard: PlanCard
    private val northPanel = JPanel(BorderLayout())
    private lateinit var titleLabel: JLabel
    private val messageContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = AppColors.pageBg
    }
    private var autoScroll = true
    private val toolCards = mutableMapOf<String, ToolCallCard>()
    /** 暂停自动滚动时显示的"滚动到底部"浮动按钮 */
    private val scrollToBottomBtn = JButton("↓").apply {
        toolTipText = "滚动到底部"
        font = font.deriveFont(16f).deriveFont(java.awt.Font.BOLD)
        foreground = AppColors.textSecondary
        background = AppColors.cardBg
        isContentAreaFilled = true
        border = BorderFactory.createLineBorder(AppColors.border)
        isFocusPainted = false
        isVisible = false
        addActionListener {
            autoScroll = true
            isVisible = false
            scrollToBottom()
        }
    }
    private val scrollPane = JScrollPane(messageContainer).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = BorderFactory.createEmptyBorder(); background = AppColors.pageBg
        verticalScrollBar.addAdjustmentListener { e ->
            if (!e.valueIsAdjusting) {
                val bar = verticalScrollBar; autoScroll =
                    bar.value + bar.visibleAmount >= bar.maximum - 50
                scrollToBottomBtn.isVisible = !autoScroll
            }
        }
    }
    private var streamingBubble: JComponent? = null
    private val streamingBuf = StringBuilder()
    private var reasoningBubble: JPanel? = null
    private var reasoningSpacer: java.awt.Component? = null
    private val reasoningBuf = StringBuilder()
    private var reasoningStartTime = 0L
    private var editorSelectionListener: EditorSelectionListener? = null
    private var disposed = false
    private val messageBusListener = object : MessageBus.MessageBusListener {
        override fun onSessionChanged(sessionId: String, type: String) {
            if (sessionId != viewModel.sessionId) return
            SwingUtilities.invokeLater {
                clearTransientUi()
                titleLabel.text = viewModel.session.title
                messageContainer.revalidate()
                messageContainer.repaint()
            }
        }

        override fun onSystemError(title: String, message: String) {
            SwingUtilities.invokeLater { showErrorBanner("$title: $message") }
        }
    }
    private var messageBusRegistered = false

    // ── MultiAgentBlock（子 Agent 进度展示）──
    private val multiAgentBlock = MultiAgentBlock(
        onSessionClick = null  // 子 session 详情跳转暂未实现
    ).apply { isVisible = false }
    private var multiAgentBlockIndex: Int = -1  // -1 = 未添加到 messageContainer

    init {
        planCard = PlanCard(
            onDeleteStep = { stepId -> viewModel.removePlanStep(stepId) }
        ).apply { isVisible = false }

        // 标题行：会话标题 + 新建会话按钮
        // 初始显示当前会话标题（默认为"新会话"），标题生成后通过 onTitleChanged 回调更新
        titleLabel = JLabel(viewModel.session.title).apply {
            font = font.deriveFont(12f).deriveFont(java.awt.Font.BOLD)
        }
        val newSessionButton = JButton("[+]").apply {
            toolTipText = "新建会话"
            font = font.deriveFont(12f)
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 2)
            addActionListener {
                viewModel.newSession()
                clearTransientUi()
                titleLabel.text = viewModel.session.title
                messageContainer.revalidate()
                messageContainer.repaint()
            }
        }
        val titleBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)
            )
            add(titleLabel, BorderLayout.CENTER)
            add(newSessionButton, BorderLayout.EAST)
        }
        // 对齐 docs/ui/components.md §6 ChatPage 组件树：
        // NORTH=标题行+PlanCard, CENTER=JScrollPane→messageContainer
        northPanel.add(titleBar, BorderLayout.NORTH)
        northPanel.add(planCard, BorderLayout.SOUTH)
        add(northPanel, BorderLayout.NORTH)
        // 使用 JLayeredPane 叠加浮动"滚动到底部"按钮
        val layeredPane = JLayeredPane().apply {
            add(scrollPane, JLayeredPane.DEFAULT_LAYER)
            add(scrollToBottomBtn, JLayeredPane.PALETTE_LAYER)
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    scrollPane.setBounds(0, 0, width, height)
                    val btnW = 36; val btnH = 36; val margin = 12
                    scrollToBottomBtn.setBounds(width - btnW - margin, height - btnH - margin, btnW, btnH)
                }
            })
        }
        add(layeredPane, BorderLayout.CENTER)

        val inputArea = ChatInputArea(
            onSend = { text ->
                viewModel.sendMessage(text)
                streamingBuf.clear(); streamingBubble = null
                reasoningBuf.clear(); reasoningBubble = null
            },
            onSendWithImages = { text, images ->
                viewModel.sendMessage(text, images)
                viewModel.updateInputState(images = emptyList())
                streamingBuf.clear(); streamingBubble = null
                reasoningBuf.clear(); reasoningBubble = null
            },
            onStop = { viewModel.cancel() },
            onNewSession = {
                viewModel.newSession()
                clearTransientUi()
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
        ).apply {
            if (enableIdeServices) setProject(project)
        }
        if (enableIdeServices) {
            editorSelectionListener = EditorSelectionListener(
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
        }
        add(inputArea, BorderLayout.SOUTH)
        registerMessageBusListener()

        viewModel.onMessageAdded = { msg ->
            removeStreamingBubble()
            if (msg.type == ChatMessage.Type.ERROR) {
                // 错误顶部给出持续可见的 Banner，同时保留消息流气泡里的复制/重试操作。
                inputArea.showError()
                showErrorBanner(msg.content)
            }
            animateBubbleAppear(renderMessage(msg))
            messageContainer.add(Box.createVerticalStrut(8))
            updateBubbleMaxWidths()
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onToolCallStarted = { toolUseId, toolName, params ->
            removeStreamingBubble()
            val paramsText = params.entries.joinToString(", ") { "${it.key}=${it.value}" }
            val card = ToolCallCard(toolName, paramsText, ToolCallCard.ToolCallState.PENDING)
            toolCards[toolUseId] = card
            animateBubbleAppear(card)
            messageContainer.add(Box.createVerticalStrut(8))
            updateBubbleMaxWidths()
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onToolCallStateChanged = { toolUseId, state, result, durationMs ->
            val uiState = runCatching { ToolCallCard.ToolCallState.valueOf(state.name) }
                .getOrDefault(ToolCallCard.ToolCallState.ERROR)
            toolCards[toolUseId]?.setState(uiState, result, durationMs)
            messageContainer.revalidate()
            messageContainer.repaint()
        }
        viewModel.onApprovalRequested = { request ->
            val card = toolCards[request.toolUseId] ?: ToolCallCard(
                request.toolName,
                request.message,
                ToolCallCard.ToolCallState.AWAITING_APPROVAL
            ).also { newCard ->
                toolCards[request.toolUseId] = newCard
                animateBubbleAppear(newCard)
                messageContainer.add(Box.createVerticalStrut(8))
            }
            card.setApprovalActions(
                ToolCallCard.ApprovalActions(
                    dangerous = request.dangerous,
                    allowSessionLabel = if (request.toolName.contains("/")) "允许此 Server" else "允许此会话",
                    onAllowOnce = {
                        request.complete(com.aiassistant.agent.ToolApprovalPolicy.ApprovalResult.ALLOW_ONCE)
                    },
                    onAllowSession = {
                        request.complete(com.aiassistant.agent.ToolApprovalPolicy.ApprovalResult.ALLOW_SESSION)
                    },
                    onReject = {
                        request.complete(com.aiassistant.agent.ToolApprovalPolicy.ApprovalResult.REJECTED)
                    }
                )
            )
            card.setState(ToolCallCard.ToolCallState.AWAITING_APPROVAL, request.message, null)
            messageContainer.revalidate()
            messageContainer.repaint()
            scrollToBottom()
        }
        viewModel.onStreamingToken = streaming@{ token ->
            if (!viewModel.isRunning) {
                removeStreamingBubble()
                streamingBuf.clear()
                return@streaming
            }
            streamingBuf.append(token)
            // ponytail: 从实际父容器移除，避免 animateBubbleAppear 包装后找不到
            removeStreamingBubble()
            streamingBubble = ChatBubbleRenderer.renderStreaming(streamingBuf.toString())
            messageContainer.add(streamingBubble!!)
            updateBubbleMaxWidths()
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onReasoningContent = { reasoning ->
            // 思考过程累积后渲染为折叠块，对齐 docs/ui/chat.md §三
            if (reasoningBuf.isEmpty()) {
                reasoningStartTime = System.currentTimeMillis()
            }
            reasoningBuf.append(reasoning)
            reasoningBubble?.let { messageContainer.remove(it) }
            reasoningSpacer?.let { messageContainer.remove(it) }
            val durationMs = System.currentTimeMillis() - reasoningStartTime
            reasoningBubble = ChatBubbleRenderer.renderThinking(reasoningBuf.toString(), durationMs)
            reasoningSpacer = Box.createVerticalStrut(8)
            // 思考过程块位于消息流末尾，没有 tool call 时在流式回复前，有 tool call 时在 tool call 前
            messageContainer.add(reasoningBubble)
            messageContainer.add(reasoningSpacer)
            updateBubbleMaxWidths()
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onStateChanged = {
            inputArea.setInputEnabled(!viewModel.isRunning)
            val plan = viewModel.session.plan
            planCard.isVisible =
                plan != null && plan.status != com.aiassistant.agent.PlanExecutor.Plan.Status.COMPLETED &&
                        plan.status != com.aiassistant.agent.PlanExecutor.Plan.Status.CANCELLED
            if (planCard.isVisible && plan != null) {
                planCard.setExecutingState(plan.status == com.aiassistant.agent.PlanExecutor.Plan.Status.EXECUTING)
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
            // 新会话不显示时间戳，保持空白
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

    private fun renderMessage(msg: ChatMessage): JComponent =
        ChatBubbleRenderer.render(
            msg,
            onRetry = if (viewModel.canRetry) ({ viewModel.retryLastTurn() }) else null,
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
            val maxWidth = when {
                component.getClientProperty("fullWidth") == true -> panelWidth
                bubbleType == "user" -> userMaxWidth
                bubbleType == "agent" || bubbleType == "error" -> agentMaxWidth
                else -> continue
            }
            // 如果 component 包含一个子面板且该子面板没有子组件，直接约束 component
            // 否则取第一个子组件为约束目标（跳过 FlowLayout 外层包装）
            val target = if (component.layout is FlowLayout && component.componentCount > 0) {
                component.getComponent(0) as? JComponent ?: component
            } else if (component is AnimatedBubbleWrapper && component.componentCount > 0) {
                // AnimatedBubbleWrapper 内层也是 FlowLayout 外层，需要再向内找
                val inner = component.getComponent(0) as? JComponent
                if (inner?.layout is FlowLayout && inner.componentCount > 0)
                    inner.getComponent(0) as? JComponent ?: component
                else
                    inner ?: component
            } else component
            if (component.getClientProperty("fullWidth") == true) {
                target.preferredSize = java.awt.Dimension(maxWidth, target.preferredSize.height)
            }
            target.maximumSize = java.awt.Dimension(maxWidth, target.preferredSize.height)
        }
        messageContainer.revalidate()
    }

    /**
     * 切换到已有会话（对齐 docs/ui/pages.md §二：CardLayout 不销毁隐藏页面，
     * ChatPage 复用同一实例，仅切换内部 session）。重置 UI 状态并重新渲染历史消息。
     */
    fun restoreSession(sessionId: String?) {
        planCard.isVisible = false
        clearTransientUi()
        autoScroll = true
        viewModel.restoreSession(sessionId)
        titleLabel.text = viewModel.session.title
        viewModel.messages.forEach { msg ->
            messageContainer.add(renderMessage(msg))
            messageContainer.add(Box.createVerticalStrut(8))
        }
        messageContainer.revalidate()
        messageContainer.repaint()
        scrollToBottom()
    }

    override fun addNotify() {
        super.addNotify()
        registerMessageBusListener()
    }

    override fun removeNotify() {
        unregisterMessageBusListener()
        super.removeNotify()
    }

    private fun registerMessageBusListener() {
        if (messageBusRegistered) return
        MessageBus.register(messageBusListener)
        messageBusRegistered = true
    }

    private fun unregisterMessageBusListener() {
        if (!messageBusRegistered) return
        MessageBus.unregister(messageBusListener)
        messageBusRegistered = false
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        unregisterMessageBusListener()
        editorSelectionListener?.dispose()
        editorSelectionListener = null
        viewModel.dispose()
    }

    private fun clearTransientUi() {
        dismissErrorBanner()
        messageContainer.removeAll()
        toolCards.clear()
        resetMultiAgentBlock()
        streamingBuf.clear()
        streamingBubble = null
        reasoningBuf.clear()
        reasoningBubble = null
        reasoningSpacer = null
        reasoningStartTime = 0L
    }

    private fun removeStreamingBubble() {
        val bubble = streamingBubble ?: return
        (bubble.parent as? JComponent)?.remove(bubble) ?: messageContainer.remove(bubble)
        streamingBubble = null
        messageContainer.revalidate()
        messageContainer.repaint()
    }

    private fun scrollToBottom() {
        if (!autoScroll) return
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    /** 当前显示的错误 Banner，新错误替换旧错误 */
    private var errorBanner: JPanel? = null

    /** 在页面顶部展示错误 Banner，点击关闭，新错误替换旧错误 */
    private fun showErrorBanner(message: String) {
        errorBanner?.let { northPanel.remove(it) }
        val bannerType = errorBannerType(message)
        val bn = Banner.create(
            message = message,
            type = bannerType,
            onClose = { dismissErrorBanner() },
            showSettingsButton = bannerType == Banner.Type.ERROR_AUTH
        )
        errorBanner = bn
        northPanel.add(bn, BorderLayout.CENTER)
        northPanel.revalidate()
        northPanel.repaint()
        revalidate(); repaint()
    }

    private fun dismissErrorBanner() {
        errorBanner?.let {
            northPanel.remove(it)
            errorBanner = null
        }
        northPanel.revalidate()
        northPanel.repaint()
        revalidate()
        repaint()
    }

    private fun errorBannerType(message: String): Banner.Type {
        val normalized = message.lowercase()
        val isAuthError = "api key" in normalized ||
                "401" in normalized ||
                "unauthorized" in normalized ||
                "认证" in message ||
                ("key" in normalized && ("无效" in message || "未配置" in message))
        return if (isAuthError) Banner.Type.ERROR_AUTH else Banner.Type.ERROR
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
        if (AppAnimations.isReducedMotionEnabled()) {
            messageContainer.add(component)
            return
        }
        val wrapper = AnimatedBubbleWrapper(component)
        messageContainer.add(wrapper)
        wrapper.startAnimation()
    }

    /**
     * 消息气泡出现动画包装器。
     * 使用 AppAnimations.Timing.BUBBLE_APPEAR 参数：durationMs/easing。
     * alpha 0→1，translateY 10→0（从下方滑入）。
     */
    private inner class AnimatedBubbleWrapper(private val child: JComponent) :
        JPanel(BorderLayout()) {
        private var alpha = 0.0f
        private var translateY = 10
        private val timer: Timer
        private var elapsed = 0
        private val durationMs = AppAnimations.Timing.BUBBLE_APPEAR.durationMs
        private val frameMs = 10 // ~100fps

        init {
            isOpaque = false
            add(child, BorderLayout.CENTER)
            // 继承 child 的 alignmentX，保持消息在 BoxLayout 容器中的左/右对齐
            alignmentX = child.alignmentX
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
                val eased = AppAnimations.Timing.BUBBLE_APPEAR.easing.apply(progress)
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
