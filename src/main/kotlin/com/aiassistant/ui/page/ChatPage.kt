package com.aiassistant.ui.page

import com.aiassistant.ui.AppColors
import com.aiassistant.ui.chat.*
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
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

    init {
        planCard = PlanCard(
            onResume = { viewModel.sendMessage("继续执行计划") },
            onPause = {
                viewModel.session.plan?.status =
                    com.aiassistant.agent.PlanExecutor.Plan.Status.PAUSED
            },
            onAbort = { viewModel.session.plan = null; planCard.isVisible = false }
        ).apply { isVisible = false }

        val titleBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.border),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
            )
            add(JLabel("🤖 Code Assistant Chat").apply {
                font = font.deriveFont(13f).deriveFont(java.awt.Font.BOLD)
            }, BorderLayout.WEST)
        }
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(planCard, BorderLayout.NORTH); centerPanel.add(
            scrollPane,
            BorderLayout.CENTER
        )
        add(titleBar, BorderLayout.NORTH); add(centerPanel, BorderLayout.CENTER)

        val inputArea = ChatInputArea(
            onSend = { text ->
                viewModel.sendMessage(text); streamingBuf.clear(); streamingBubble = null
            },
            onStop = { viewModel.cancel() },
            onNewSession = {
                viewModel.newSession()
                messageContainer.removeAll()
                toolCards.clear()
                addTimestampMarker()
                messageContainer.revalidate()
                messageContainer.repaint()
            }
        ).apply { setProject(project) }
        add(inputArea, BorderLayout.SOUTH)

        viewModel.onMessageAdded = { msg ->
            streamingBubble?.let { messageContainer.remove(it); streamingBubble = null }
            messageContainer.add(renderMessage(msg))
            messageContainer.add(Box.createVerticalStrut(8))
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onToolCallStarted = { toolUseId, toolName, params ->
            streamingBubble?.let { messageContainer.remove(it); streamingBubble = null }
            val paramsText = params.entries.joinToString(", ") { "${it.key}=${it.value}" }
            val card = ToolCallCard(toolName, paramsText, ToolCallCard.ToolCallState.PENDING)
            toolCards[toolUseId] = card
            messageContainer.add(card)
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
            streamingBubble = ChatBubbleRenderer.render(
                ChatMessage(
                    type = ChatMessage.Type.AGENT_TEXT,
                    content = streamingBuf.toString(),
                    timestamp = java.time.Instant.now()
                )
            )
            messageContainer.add(streamingBubble)
            messageContainer.revalidate(); scrollToBottom()
        }
        viewModel.onStateChanged = {
            inputArea.setInputEnabled(!viewModel.isRunning)
            val plan = viewModel.session.plan
            planCard.isVisible =
                plan != null && plan.status == com.aiassistant.agent.PlanExecutor.Plan.Status.PAUSED
            if (planCard.isVisible && plan != null) {
                planCard.setPlan(plan.summary, plan.steps.map {
                    PlanCard.StepRow(it.id, it.description, "工具: ${it.tool}")
                })
            }
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
            onRetry = if (viewModel.canRetry) ({ viewModel.retryLastTurn() }) else null
        )

    private fun scrollToBottom() {
        if (!autoScroll) return
        SwingUtilities.invokeLater {
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }
}
