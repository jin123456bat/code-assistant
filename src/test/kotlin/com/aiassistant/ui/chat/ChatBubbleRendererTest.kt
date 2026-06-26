package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.assertTrue

class ChatBubbleRendererTest {

    @Test
    fun `renders tool call messages as tool call cards`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.TOOL_CALL,
                content = "Read",
                toolCall = ToolCallUIData(
                    toolUseId = "tool-1",
                    toolName = "Read",
                    state = "DONE",
                    result = "ok",
                    durationMs = 12
                )
            )
        )

        assertIs<ToolCallCard>(component)
    }

    @Test
    fun `renders approval state in tool call cards`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.TOOL_CALL,
                content = "command=./gradlew test",
                toolCall = ToolCallUIData(
                    toolUseId = "tool-2",
                    toolName = "Bash",
                    state = "AWAITING_APPROVAL"
                )
            )
        ) as ToolCallCard

        val labels = labelsIn(component).mapNotNull { it.text }
        assertContains(labels.joinToString("\n"), "等待授权")
    }

    @Test
    fun `renders rejected state in tool call cards`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.TOOL_CALL,
                content = "command=./gradlew test",
                toolCall = ToolCallUIData(
                    toolUseId = "tool-3",
                    toolName = "Bash",
                    state = "REJECTED",
                    result = "用户拒绝执行工具: Bash"
                )
            )
        ) as ToolCallCard

        val labels = labelsIn(component).mapNotNull { it.text }
        assertContains(labels.joinToString("\n"), "已拒绝")
    }

    @Test
    fun `error copy button has an action`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.ERROR,
                content = "Something failed"
            )
        )

        val copyButton = buttonsIn(component).single { it.text == "📋 复制" }
        assertTrue(copyButton.actionListeners.isNotEmpty())
    }

    @Test
    fun `error retry button invokes callback`() {
        var retried = false
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.ERROR,
                content = "Something failed"
            ),
            onRetry = { retried = true }
        )

        buttonsIn(component).single { it.text == "🔄 重试" }.doClick()

        assertTrue(retried)
    }

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }

    private fun buttonsIn(container: Container): List<JButton> =
        container.components.flatMap { child ->
            when (child) {
                is JButton -> listOf(child)
                is Container -> buttonsIn(child)
                else -> emptyList()
            }
        }
}
