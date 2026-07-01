package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.assertFalse
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
    fun `approval card buttons invoke approval callbacks`() {
        var approvedOnce = false
        var approvedSession = false
        var rejected = false

        approvalCard(
            onAllowOnce = { approvedOnce = true },
            onAllowSession = { approvedSession = true },
            onReject = { rejected = true }
        ).let { buttonsIn(it).single { button -> button.text == "允许一次" }.doClick() }
        approvalCard(
            onAllowOnce = { approvedOnce = true },
            onAllowSession = { approvedSession = true },
            onReject = { rejected = true }
        ).let { buttonsIn(it).single { button -> button.text == "允许此会话" }.doClick() }
        approvalCard(
            onAllowOnce = { approvedOnce = true },
            onAllowSession = { approvedSession = true },
            onReject = { rejected = true }
        ).let { buttonsIn(it).single { button -> button.text == "拒绝" }.doClick() }

        assertTrue(approvedOnce)
        assertTrue(approvedSession)
        assertTrue(rejected)
    }

    @Test
    fun `dangerous approval card hides allow session button`() {
        val card = ToolCallCard(
            toolName = "Bash",
            params = "command=sudo rm -rf /tmp/demo",
            initialState = ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            approvalActions = ToolCallCard.ApprovalActions(
                dangerous = true,
                onAllowOnce = {},
                onAllowSession = {},
                onReject = {}
            )
        )

        val buttonTexts = buttonsIn(card).map { it.text }
        assertContains(buttonTexts, "允许一次")
        assertContains(buttonTexts, "拒绝")
        assertFalse("允许此会话" in buttonTexts)
    }

    @Test
    fun `mcp approval card labels session approval as server approval`() {
        val card = ToolCallCard(
            toolName = "docs/search",
            params = "query=hello",
            initialState = ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            approvalActions = ToolCallCard.ApprovalActions(
                dangerous = false,
                allowSessionLabel = "允许此 Server",
                onAllowOnce = {},
                onAllowSession = {},
                onReject = {}
            )
        )

        val buttonTexts = buttonsIn(card).map { it.text }
        assertContains(buttonTexts, "允许此 Server")
        assertFalse("允许此会话" in buttonTexts)
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

    @Test
    fun `agent bubble does not render feedback buttons`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                id = "agent-1",
                type = ChatMessage.Type.AGENT_TEXT,
                content = "done"
            )
        )

        val buttonTexts = buttonsIn(component).map { it.text }
        assertFalse("👍" in buttonTexts)
        assertFalse("👎" in buttonTexts)
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

    private fun approvalCard(
        onAllowOnce: () -> Unit,
        onAllowSession: () -> Unit,
        onReject: () -> Unit
    ): ToolCallCard =
        ToolCallCard(
            toolName = "Bash",
            params = "command=./gradlew test",
            initialState = ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            approvalActions = ToolCallCard.ApprovalActions(
                dangerous = false,
                onAllowOnce = onAllowOnce,
                onAllowSession = onAllowSession,
                onReject = onReject
            )
        )
}
