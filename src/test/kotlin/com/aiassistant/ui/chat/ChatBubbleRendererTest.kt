package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertSame

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
    fun `tool card shows child token cost after it is set`() {
        val card = ToolCallCard(
            toolName = "SubAgent",
            params = "task=review",
            initialState = ToolCallCard.ToolCallState.DONE
        )

        card.setChildTokenCost(1000, 2000)

        val labels = labelsIn(card).mapNotNull { it.text }
        assertTrue(labels.any { it.contains("子任务 Token: 1000 in / 2000 out") })
    }

    @Test
    fun `tool cards do not allow box layout to stretch their height`() {
        listOf(
            ToolCallCard("glob", "pattern=**/*.kt", ToolCallCard.ToolCallState.AWAITING_APPROVAL),
            ToolCallCard("bash", "command=./gradlew test", ToolCallCard.ToolCallState.PENDING),
            ToolCallCard("bash", "command=./gradlew test", ToolCallCard.ToolCallState.EXECUTING)
        ).forEach { card ->
            assertEquals(card.preferredSize.height, card.maximumSize.height)
        }
    }

    @Test
    fun `completed tool cards are collapsed by default`() {
        listOf(
            ToolCallCard.ToolCallState.DONE,
            ToolCallCard.ToolCallState.ERROR,
            ToolCallCard.ToolCallState.TIMEOUT,
            ToolCallCard.ToolCallState.REJECTED,
            ToolCallCard.ToolCallState.CANCELLED
        ).forEach { state ->
            val card = ToolCallCard("bash", "command=./gradlew test", state)

            assertContains(
                labelsIn(card).mapNotNull { it.text },
                "▶",
                "$state 工具卡片默认应为折叠状态"
            )
        }
    }

    @Test
    fun `tool card collapses after active state reaches a terminal state`() {
        listOf(
            ToolCallCard.ToolCallState.EXECUTING to ToolCallCard.ToolCallState.DONE,
            ToolCallCard.ToolCallState.EXECUTING to ToolCallCard.ToolCallState.ERROR,
            ToolCallCard.ToolCallState.EXECUTING to ToolCallCard.ToolCallState.TIMEOUT,
            ToolCallCard.ToolCallState.EXECUTING to ToolCallCard.ToolCallState.CANCELLED,
            ToolCallCard.ToolCallState.AWAITING_APPROVAL to ToolCallCard.ToolCallState.REJECTED
        ).forEach { (activeState, terminalState) ->
            val card = ToolCallCard("bash", "command=./gradlew test", activeState)

            card.setState(terminalState, "result", 1200)

            assertContains(
                labelsIn(card).mapNotNull { it.text },
                "▶",
                "$activeState 转为 $terminalState 后应折叠"
            )
        }
    }

    @Test
    fun `approval and executing tool cards stay expanded`() {
        listOf(
            ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            ToolCallCard.ToolCallState.EXECUTING
        ).forEach { state ->
            val card = ToolCallCard("bash", "command=./gradlew test", state)
            val header = card.getComponent(0)

            header.dispatchEvent(
                java.awt.event.MouseEvent(
                    header,
                    java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    1,
                    false
                )
            )

            assertContains(labelsIn(card).mapNotNull { it.text }, "▾")
        }
    }

    @Test
    fun `repeated terminal state update preserves manual expansion`() {
        val card = ToolCallCard(
            "bash",
            "command=./gradlew test",
            ToolCallCard.ToolCallState.DONE
        )
        val header = card.getComponent(0)
        header.dispatchEvent(
            java.awt.event.MouseEvent(
                header,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        card.setState(ToolCallCard.ToolCallState.DONE, "BUILD SUCCESSFUL", 1200)

        assertContains(labelsIn(card).mapNotNull { it.text }, "▾")
    }

    @Test
    fun `tool card params avoid swing html css`() {
        val card =
            ToolCallCard("glob", "pattern=**/*.kt", ToolCallCard.ToolCallState.AWAITING_APPROVAL)

        assertTrue(labelsIn(card).any { it.text == "pattern=**/*.kt" })
        assertTrue(labelsIn(card).none { it.text?.contains("style=") == true })
    }

    @Test
    fun `tool card stops rotation timer when removed`() {
        val card =
            ToolCallCard("bash", "command=./gradlew test", ToolCallCard.ToolCallState.EXECUTING)
        val timer = ToolCallCard::class.java.getDeclaredField("rotationTimer").let { field ->
            field.isAccessible = true
            field.get(card) as javax.swing.Timer
        }

        assertTrue(timer.isRunning)
        card.removeNotify()

        assertFalse(timer.isRunning)
    }

    @Test
    fun `approval message renders inline instead of result scroll pane`() {
        val card = ToolCallCard(
            "glob",
            "pattern=**/*.kt",
            ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            approvalActions = ToolCallCard.ApprovalActions(
                dangerous = false,
                onAllowOnce = {},
                onAllowSession = {},
                onReject = {}
            )
        )

        card.setState(
            ToolCallCard.ToolCallState.AWAITING_APPROVAL,
            "首次使用 glob 工具，需要你的授权"
        )

        assertContains(labelsIn(card).mapNotNull { it.text }.joinToString("\n"), "首次使用 glob")
        assertTrue(scrollPanesIn(card).none { it.isVisible })
    }

    @Test
    fun `empty pending tool card does not show an empty expanded body`() {
        val card = ToolCallCard("bash", "", ToolCallCard.ToolCallState.PENDING)
        val collapsedHeight = card.preferredSize.height
        val header = card.getComponent(0)

        header.dispatchEvent(
            java.awt.event.MouseEvent(
                header,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        assertEquals(collapsedHeight, card.preferredSize.height)
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

    @Test
    fun `agent bubble exposes its row type on the outer component`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "done")
        )

        assertEquals("agent", component.getClientProperty("bubbleType"))
    }

    @Test
    fun `agent bubble keeps text left aligned inside a rounded card`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "hello")
        )

        val card = panelsIn(component).firstOrNull { panel ->
            panel.background == com.aiassistant.ui.AppColors.cardBg &&
                    borderContainsRoundedBorder(panel.border)
        }

        assertNotNull(card)
        labelsIn(component)
            .filter { it.text?.contains("hello") == true }
            .forEach { label ->
                assertEquals(java.awt.Component.LEFT_ALIGNMENT, label.alignmentX)
            }
    }

    @Test
    fun `agent text labels stay left aligned while allowing width updates`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "hello\n- world")
        )

        labelsIn(component)
            .filter { it.text?.contains("hello") == true || it.text?.contains("world") == true }
            .forEach { label ->
                assertEquals(javax.swing.SwingConstants.LEFT, label.horizontalAlignment)
                assertTrue(label.maximumSize.width >= label.preferredSize.width)
            }
    }

    @Test
    fun `short user bubble keeps content width`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.USER_TEXT, content = "你好"),
            panelWidth = 900
        )

        assertTrue(component.getComponent(0).preferredSize.width < 180)
    }

    @Test
    fun `wrapping labels can be retargeted to a narrower width`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "hello world hello world"),
            panelWidth = 800
        )

        ChatBubbleRenderer.updateWrappingLabels(component, 120)

        assertTrue(
            labelsIn(component)
                .filter { it.text?.contains("hello") == true }
                .all { it.text.contains("body width='120'") }
        )
    }

    @Test
    fun `thinking block marks itself as full width`() {
        val component = ChatBubbleRenderer.renderThinking("thinking", 300)

        assertEquals(true, component.getClientProperty("fullWidth"))
    }

    @Test
    fun `chat rows do not allow box layout to stretch their height`() {
        val rows = listOf(
            ChatBubbleRenderer.render(
                ChatMessage(
                    type = ChatMessage.Type.USER_TEXT,
                    content = "hello"
                )
            ),
            ChatBubbleRenderer.render(
                ChatMessage(
                    type = ChatMessage.Type.AGENT_TEXT,
                    content = "hello"
                )
            ),
            ChatBubbleRenderer.renderThinking("thinking", 300)
        )

        rows.forEach { row ->
            assertEquals(row.preferredSize.height, row.maximumSize.height)
        }
    }

    @Test
    fun `streaming agent bubble keeps one component through updates and completion`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        val component = handle.component

        handle.updateStreaming("first")
        handle.updateStreaming("second")
        handle.finish(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = "final",
                tokenDelta = ChatMessage.TokenDelta(input = 1000, output = 2000)
            )
        )

        assertSame(component, handle.component)
        assertFalse(labelsIn(component).any { it.text == "▍" && it.isVisible })
        assertTrue(labelsIn(component).any { it.text?.contains("final") == true })
        val roundedCard = panelsIn(component).single { borderContainsRoundedBorder(it.border) }
        assertFalse(labelsIn(roundedCard).any { it.text?.contains("↑1K ↓2K") == true })
        assertTrue(labelsIn(component).any { it.text?.contains("↑1K ↓2K") == true })
    }

    @Test
    fun `streaming paragraph component survives content width and completion updates`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("Hello")
        val paragraph = labelsIn(handle.component).first { it.text?.contains("Hello") == true }

        handle.updateStreaming("Hello world")
        assertSame(paragraph, labelsIn(handle.component).first { it.text?.contains("Hello world") == true })

        handle.constrainWidth(220)
        assertSame(paragraph, labelsIn(handle.component).first { it.text?.contains("Hello world") == true })

        handle.finish(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = "Hello world",
                tokenDelta = ChatMessage.TokenDelta(input = 1000, output = 1000)
            )
        )
        assertSame(paragraph, labelsIn(handle.component).first { it.text?.contains("Hello world") == true })
    }

    @Test
    fun `completed markdown prefix component survives tail updates`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("First paragraph\n\nSecond")
        val firstParagraph = labelsIn(handle.component).first {
            it.text?.contains("First paragraph") == true
        }

        handle.updateStreaming("First paragraph\n\nSecond paragraph grows")

        assertSame(
            firstParagraph,
            labelsIn(handle.component).first { it.text?.contains("First paragraph") == true }
        )
    }

    @Test
    fun `code component survives content and width updates`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("```kotlin\nval x = 1\n```")
        val codePane = textPanesIn(handle.component).single()

        handle.updateStreaming("```kotlin\nval x = 12\n```")
        assertSame(codePane, textPanesIn(handle.component).single())
        assertContains(codePane.text, "val x = 12")

        handle.constrainWidth(640)
        assertSame(codePane, textPanesIn(handle.component).single())
    }

    @Test
    fun `code blocks forward vertical wheel events to outer message scroll`() {
        val completed = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = "```kotlin\nval completed = true\n```"
            )
        )
        val streaming = ChatBubbleRenderer.createStreamingAgentBubble(
            "```kotlin\nval streaming = true\n```"
        ).component

        listOf(completed, streaming).forEach { bubble ->
            val outerScrollPane = JScrollPane(bubble)
            val codeScrollPane = scrollPanesIn(bubble).single {
                it.verticalScrollBarPolicy == JScrollPane.VERTICAL_SCROLLBAR_NEVER
            }
            codeScrollPane.horizontalScrollBar.setValues(10, 20, 0, 100)
            codeScrollPane.horizontalScrollBar.unitIncrement = 3
            var forwardedEvents = 0
            outerScrollPane.addMouseWheelListener { forwardedEvents++ }

            codeScrollPane.dispatchEvent(
                java.awt.event.MouseWheelEvent(
                    codeScrollPane,
                    java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    0,
                    false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    1
                )
            )

            assertEquals(1, forwardedEvents)
            assertEquals(10, codeScrollPane.horizontalScrollBar.value)

            codeScrollPane.dispatchEvent(
                java.awt.event.MouseWheelEvent(
                    codeScrollPane,
                    java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK,
                    1,
                    1,
                    0,
                    false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    1
                )
            )

            assertEquals(1, forwardedEvents, "Shift + 滚轮应保留给代码块横向滚动")
            assertTrue(codeScrollPane.horizontalScrollBar.value > 10)
        }
    }

    @Test
    fun `closing code fence replaces only the draft tail`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("Intro\n\n```kotlin\nval x = 1")
        val intro = labelsIn(handle.component).first { it.text?.contains("Intro") == true }

        handle.updateStreaming("Intro\n\n```kotlin\nval x = 1\n```")

        assertSame(intro, labelsIn(handle.component).first { it.text?.contains("Intro") == true })
        assertEquals(1, textPanesIn(handle.component).size)
    }

    @Test
    fun `tail tokens do not rewrite a completed code document`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("```kotlin\nval x = 1\n```\n\nTail")
        val codeDocument = textPanesIn(handle.component).single().document
        var documentChanges = 0
        codeDocument.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { documentChanges++ }
            override fun removeUpdate(e: DocumentEvent?) { documentChanges++ }
            override fun changedUpdate(e: DocumentEvent?) { documentChanges++ }
        })

        handle.appendStreaming(" grows")

        assertEquals(0, documentChanges)
    }

    @Test
    fun `adding a markdown block does not detach the completed prefix from body`() {
        val handle = ChatBubbleRenderer.createStreamingAgentBubble()
        handle.updateStreaming("First paragraph")
        val body = handle.markdownBody.component
        var removedChildren = 0
        body.addContainerListener(object : java.awt.event.ContainerAdapter() {
            override fun componentRemoved(e: java.awt.event.ContainerEvent?) {
                removedChildren++
            }
        })

        handle.appendStreaming("\n\n# Header")

        assertEquals(0, removedChildren)
    }

    @Test
    fun `renderer owns agent bubble width constraint`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(
                type = ChatMessage.Type.AGENT_TEXT,
                content = "This is a long assistant response that must wrap inside the available width."
            )
        )

        ChatBubbleRenderer.constrainWidth(component, 180)

        val roundedCard = panelsIn(component).single { borderContainsRoundedBorder(it.border) }
        assertTrue(roundedCard.maximumSize.width <= 180)
        assertTrue(labelsIn(roundedCard).any { it.text?.contains("body width=") == true })
    }

    @Test
    fun `thinking expanded body keeps prototype height cap`() {
        val component = ChatBubbleRenderer.renderThinking("line\n".repeat(80), 300)
        val collapsedHeight = component.maximumSize.height
        val thinkingLabel = labelsIn(component).first { it.text == "💭 思考过程" }
        thinkingLabel.dispatchEvent(
            java.awt.event.MouseEvent(
                thinkingLabel,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        val visibleScroll = scrollPanesIn(component).single { it.isVisible }
        assertTrue(visibleScroll.preferredSize.height <= 140)
        assertTrue(component.maximumSize.height > collapsedHeight)
    }

    @Test
    fun `thinking expands after width constraint is applied`() {
        val component = ChatBubbleRenderer.renderThinking("line\n".repeat(20), 300)
        component.preferredSize = java.awt.Dimension(400, component.preferredSize.height)
        component.maximumSize = java.awt.Dimension(400, component.preferredSize.height)
        val collapsedHeight = component.maximumSize.height
        val thinkingLabel = labelsIn(component).first { it.text == "💭 思考过程" }

        thinkingLabel.dispatchEvent(
            java.awt.event.MouseEvent(
                thinkingLabel,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        assertTrue(scrollPanesIn(component).single { it.isVisible }.preferredSize.height > 0)
        assertTrue(component.maximumSize.height > collapsedHeight)
    }

    @Test
    fun `thinking expanded body wraps to current width`() {
        val component = ChatBubbleRenderer.renderThinking(
            "The user keeps saying hello repeatedly and this sentence should wrap inside the thinking panel.",
            300
        )
        component.preferredSize = java.awt.Dimension(320, component.preferredSize.height)
        component.maximumSize = java.awt.Dimension(320, component.preferredSize.height)
        val thinkingLabel = labelsIn(component).first { it.text == "💭 思考过程" }

        thinkingLabel.dispatchEvent(
            java.awt.event.MouseEvent(
                thinkingLabel,
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                1,
                1,
                1,
                false
            )
        )

        val body = textAreasIn(component).single()
        assertTrue(body.lineWrap)
        assertTrue(body.preferredSize.width <= 300)
    }

    @Test
    fun `inline code renders as html code element`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "Use `foo()` now")
        )

        val labelText = labelsIn(component).mapNotNull { it.text }.joinToString("\n")
        assertContains(labelText, "<code")
        assertFalse("&lt;code" in labelText)
    }

    @Test
    fun `unclosed code fence stays as paragraph while streaming`() {
        val component = ChatBubbleRenderer.render(
            ChatMessage(type = ChatMessage.Type.AGENT_TEXT, content = "```kotlin\nval x = 1")
        )

        assertTrue(textPanesIn(component).isEmpty())
        assertContains(labelsIn(component).mapNotNull { it.text }.joinToString("\n"), "```kotlin")
    }

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }

    private fun panelsIn(container: Container): List<javax.swing.JPanel> =
        container.components.flatMap { child ->
            when (child) {
                is javax.swing.JPanel -> listOf(child) + panelsIn(child)
                is Container -> panelsIn(child)
                else -> emptyList()
            }
        }

    private fun borderContainsRoundedBorder(border: Border?): Boolean =
        when (border) {
            null -> false
            is com.aiassistant.ui.RoundedBorder -> true
            is CompoundBorder -> borderContainsRoundedBorder(border.outsideBorder) ||
                    borderContainsRoundedBorder(border.insideBorder)

            else -> false
        }

    private fun textPanesIn(container: Container): List<JTextPane> =
        container.components.flatMap { child ->
            when (child) {
                is JTextPane -> listOf(child)
                is Container -> textPanesIn(child)
                else -> emptyList()
            }
        }

    private fun textAreasIn(container: Container): List<JTextArea> =
        container.components.flatMap { child ->
            when (child) {
                is JTextArea -> listOf(child)
                is Container -> textAreasIn(child)
                else -> emptyList()
            }
        }

    private fun scrollPanesIn(container: Container): List<JScrollPane> =
        container.components.flatMap { child ->
            when (child) {
                is JScrollPane -> listOf(child)
                is Container -> scrollPanesIn(child)
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
