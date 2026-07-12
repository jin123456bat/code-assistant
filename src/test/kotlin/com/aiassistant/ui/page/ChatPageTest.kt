package com.aiassistant.ui.page

import com.intellij.openapi.project.Project
import com.aiassistant.ui.MessageBus
import com.aiassistant.ui.chat.ChatBubbleRenderer
import com.aiassistant.ui.chat.ChatViewModel
import java.awt.BorderLayout
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ChatPageTest {

    @Test
    fun `error banner does not replace chat page north panel`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val layout = page.layout as BorderLayout
        val northBefore = layout.getLayoutComponent(BorderLayout.NORTH)

        val method = ChatPage::class.java.getDeclaredMethod("showErrorBanner", String::class.java)
        method.isAccessible = true
        method.invoke(page, "network failed")

        val northAfter = layout.getLayoutComponent(BorderLayout.NORTH)
        assertSame(northBefore, northAfter)
        val topPanel = assertIs<JPanel>(northAfter)
        assertIs<JPanel>((topPanel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        assertNotNull(buttonsIn(page).firstOrNull { it.text == "✕" })
    }

    @Test
    fun `api key error banner shows settings action`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )

        val method = ChatPage::class.java.getDeclaredMethod("showErrorBanner", String::class.java)
        method.isAccessible = true
        method.invoke(page, "API Key 无效")

        val settingsButton = buttonsIn(page).firstOrNull { it.text.contains("Settings") }
        assertNotNull(settingsButton)
    }

    @Test
    fun `generic invalid error banner does not show settings action`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )

        val method = ChatPage::class.java.getDeclaredMethod("showErrorBanner", String::class.java)
        method.isAccessible = true
        method.invoke(page, "参数无效")

        val settingsButton = buttonsIn(page).firstOrNull { it.text.contains("Settings") }
        assertNull(settingsButton)
    }

    @Test
    fun `restore session clears stale error banner`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val method = ChatPage::class.java.getDeclaredMethod("showErrorBanner", String::class.java)
        method.isAccessible = true
        method.invoke(page, "network failed")

        page.restoreSession(null)

        val topPanel =
            (page.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JPanel
        assertNull((topPanel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
    }

    @Test
    fun `system error event shows error banner`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        try {
            MessageBus.publishSystemError("插件内部错误", "background failed")
            SwingUtilities.invokeAndWait {}

            val text = labelsIn(page).mapNotNull { it.text }.joinToString("\n")
            kotlin.test.assertTrue(text.contains("background failed"))
        } finally {
            page.removeNotify()
        }
    }

    @Test
    fun `session changed event clears rendered chat messages`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        try {
            val viewModelField = ChatPage::class.java.getDeclaredField("viewModel")
            viewModelField.isAccessible = true
            val viewModel = viewModelField.get(page) as ChatViewModel
            val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
            messageContainerField.isAccessible = true
            val messageContainer = messageContainerField.get(page) as JPanel
            messageContainer.add(JLabel("stale message"))

            MessageBus.publishSessionChanged(viewModel.sessionId, "CLEARED")
            SwingUtilities.invokeAndWait {}

            kotlin.test.assertFalse(labelsIn(page).any { it.text == "stale message" })
        } finally {
            page.removeNotify()
        }
    }

    @Test
    fun `session changed event for another session does not clear rendered chat messages`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        try {
            val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
            messageContainerField.isAccessible = true
            val messageContainer = messageContainerField.get(page) as JPanel
            messageContainer.add(JLabel("current message"))

            MessageBus.publishSessionChanged("other-session", "CLEARED")
            SwingUtilities.invokeAndWait {}

            kotlin.test.assertTrue(labelsIn(page).any { it.text == "current message" })
        } finally {
            page.removeNotify()
        }
    }

    @Test
    fun `single reasoning row has no trailing spacer`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModelField = ChatPage::class.java.getDeclaredField("viewModel")
        viewModelField.isAccessible = true
        val viewModel = viewModelField.get(page) as ChatViewModel
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as JPanel

        SwingUtilities.invokeAndWait {
            viewModel.onReasoningContent?.invoke("first")
            viewModel.onReasoningContent?.invoke(" second")
        }

        kotlin.test.assertEquals(1, messageContainer.componentCount)
    }

    @Test
    fun `user reasoning and agent rows have one eight pixel gap between each row`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModel = ChatPage::class.java.getDeclaredField("viewModel").run {
            isAccessible = true
            get(page) as ChatViewModel
        }
        val messageContainer = ChatPage::class.java.getDeclaredField("messageContainer").run {
            isAccessible = true
            get(page) as JPanel
        }
        ChatViewModel::class.java.getDeclaredField("turnInFlight").apply {
            isAccessible = true
            setBoolean(viewModel, true)
        }

        SwingUtilities.invokeAndWait {
            viewModel.onMessageAdded?.invoke(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.USER_TEXT,
                    content = "hello"
                )
            )
            viewModel.onReasoningContent?.invoke("thinking")
            viewModel.onStreamingToken?.invoke("answer")
        }

        kotlin.test.assertEquals(5, messageContainer.componentCount)
        listOf(1, 3).forEach { index ->
            val gap = messageContainer.getComponent(index) as JComponent
            kotlin.test.assertEquals(true, gap.getClientProperty("messageRowGap"))
            kotlin.test.assertEquals(8, gap.preferredSize.height)
        }
    }

    @Test
    fun `reasoning updates keep the same component`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModelField = ChatPage::class.java.getDeclaredField("viewModel").apply {
            isAccessible = true
        }
        val viewModel = viewModelField.get(page) as ChatViewModel
        val reasoningBubbleField = ChatPage::class.java.getDeclaredField("reasoningBubble").apply {
            isAccessible = true
        }

        SwingUtilities.invokeAndWait {
            viewModel.onReasoningContent?.invoke("first")
        }
        val firstComponent = reasoningBubbleField.get(page)

        SwingUtilities.invokeAndWait {
            viewModel.onReasoningContent?.invoke(" second")
        }

        val handle = reasoningBubbleField.get(page) as ChatBubbleRenderer.ThinkingHandle
        assertSame(firstComponent, handle)
        kotlin.test.assertEquals("first second", handle.body.text)
        assertTrue(labelsIn(page).any { it.text == "💭 思考过程" })
    }

    @Test
    fun `late streaming tokens are ignored after the turn ends`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModelField = ChatPage::class.java.getDeclaredField("viewModel")
        viewModelField.isAccessible = true
        val viewModel = viewModelField.get(page) as ChatViewModel
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as JPanel

        SwingUtilities.invokeAndWait {
            viewModel.onStreamingToken?.invoke("late")
        }

        kotlin.test.assertEquals(0, messageContainer.componentCount)
    }

    @Test
    fun `reasoning block uses safe available width when added`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModelField = ChatPage::class.java.getDeclaredField("viewModel")
        viewModelField.isAccessible = true
        val viewModel = viewModelField.get(page) as ChatViewModel
        val scrollPaneField = ChatPage::class.java.getDeclaredField("scrollPane")
        scrollPaneField.isAccessible = true
        val scrollPane = scrollPaneField.get(page) as javax.swing.JScrollPane
        scrollPane.viewport.setSize(600, 400)
        val reasoningBubbleField = ChatPage::class.java.getDeclaredField("reasoningBubble")
        reasoningBubbleField.isAccessible = true

        SwingUtilities.invokeAndWait {
            viewModel.onReasoningContent?.invoke("thinking")
            viewModel.onReasoningContent?.invoke(" continues")
        }

        val reasoningBubble = reasoningBubbleField.get(page) as ChatBubbleRenderer.ThinkingHandle
        kotlin.test.assertEquals(584, reasoningBubble.component.preferredSize.width)
    }

    @Test
    fun `width updates do not stretch short user or agent bubbles`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as JPanel
        val scrollPaneField = ChatPage::class.java.getDeclaredField("scrollPane")
        scrollPaneField.isAccessible = true
        val scrollPane = scrollPaneField.get(page) as javax.swing.JScrollPane
        scrollPane.viewport.setSize(800, 400)

        SwingUtilities.invokeAndWait {
            val user = com.aiassistant.ui.chat.ChatBubbleRenderer.render(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.USER_TEXT,
                    content = "你好"
                )
            )
            val agent = com.aiassistant.ui.chat.ChatBubbleRenderer.render(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.AGENT_TEXT,
                    content = "你好"
                )
            )
            messageContainer.add(user)
            messageContainer.add(agent)
            val userWidthBefore = user.getComponent(0).preferredSize.width
            val agentWidthBefore = agent.getComponent(0).preferredSize.width

            page.updateBubbleMaxWidths()

            assertTrue(user.getComponent(0).preferredSize.width <= userWidthBefore + 2)
            assertTrue(agent.getComponent(0).preferredSize.width <= agentWidthBefore + 2)
            assertTrue(labelsIn(user).none { it.text?.contains("body width=") == true })
        }
    }

    @Test
    fun `message container tracks viewport width`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as Scrollable

        assertTrue(messageContainer.getScrollableTracksViewportWidth())
    }

    @Test
    fun `streaming completion keeps the same agent bubble component`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val viewModelField = ChatPage::class.java.getDeclaredField("viewModel").apply {
            isAccessible = true
        }
        val viewModel = viewModelField.get(page) as ChatViewModel
        ChatViewModel::class.java.getDeclaredField("turnInFlight").apply {
            isAccessible = true
            setBoolean(viewModel, true)
        }
        val streamingBubbleField = ChatPage::class.java.getDeclaredField("streamingBubble").apply {
            isAccessible = true
        }

        SwingUtilities.invokeAndWait {
            viewModel.onStreamingToken?.invoke("first")
        }
        val handle = streamingBubbleField.get(page) as com.aiassistant.ui.chat.ChatBubbleRenderer.AgentBubbleHandle
        val component = handle.component

        SwingUtilities.invokeAndWait {
            viewModel.onStreamingToken?.invoke(" second")
            viewModel.onMessageAdded?.invoke(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.AGENT_TEXT,
                    content = "final"
                )
            )
        }

        assertSame(component, handle.component)
        assertNull(streamingBubbleField.get(page))
        assertTrue(labelsIn(component).any { it.text?.contains("final") == true })
    }

    @Test
    fun `width updates shrink oversized message and thinking blocks after sidebar resize`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as JPanel
        val scrollPaneField = ChatPage::class.java.getDeclaredField("scrollPane")
        scrollPaneField.isAccessible = true
        val scrollPane = scrollPaneField.get(page) as javax.swing.JScrollPane
        scrollPane.viewport.setSize(320, 400)

        SwingUtilities.invokeAndWait {
            val user = com.aiassistant.ui.chat.ChatBubbleRenderer.render(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.USER_TEXT,
                    content = "你好，这是一条很长的用户消息，用来模拟右侧栏收窄后的宽度约束"
                ),
                panelWidth = 900
            )
            val agent = com.aiassistant.ui.chat.ChatBubbleRenderer.render(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.AGENT_TEXT,
                    content = "你好，这是一条很长的 AI 消息，用来确认文本在窄面板里会重新换行而不是被裁剪"
                ),
                panelWidth = 900
            )
            val thinking = com.aiassistant.ui.chat.ChatBubbleRenderer.renderThinking(
                "这是思考过程内容，用来确认 fullWidth 组件在侧边栏拖动后不会顶到右侧边界。",
                1200
            )
            messageContainer.add(user)
            messageContainer.add(agent)
            messageContainer.add(thinking)

            page.updateBubbleMaxWidths()

            val usableWidth = 304
            assertTrue((user.getComponent(0) as JComponent).maximumSize.width <= usableWidth)
            assertTrue(labelsIn(agent).any { it.text?.contains("body width=") == true })
            assertTrue(thinking.maximumSize.width <= usableWidth)
        }
    }

    @Test
    fun `width updates refresh agent row height after wrapping`() {
        val page = ChatPage(
            project = projectAt(createTempDirectory().toString()),
            enableIdeServices = false
        )
        val messageContainerField = ChatPage::class.java.getDeclaredField("messageContainer")
        messageContainerField.isAccessible = true
        val messageContainer = messageContainerField.get(page) as JPanel
        val scrollPaneField = ChatPage::class.java.getDeclaredField("scrollPane")
        scrollPaneField.isAccessible = true
        val scrollPane = scrollPaneField.get(page) as javax.swing.JScrollPane
        scrollPane.viewport.setSize(320, 400)

        SwingUtilities.invokeAndWait {
            val agent = com.aiassistant.ui.chat.ChatBubbleRenderer.render(
                com.aiassistant.ui.chat.ChatMessage(
                    type = com.aiassistant.ui.chat.ChatMessage.Type.AGENT_TEXT,
                    content = """
                        你好！这是一条比较长的 AI 消息，用来复现侧边栏变窄后文本需要重新换行的场景。
                        - 第一条列表内容也必须按新的宽度换行，并且仍然贴着气泡左侧开始显示。
                        - 第二条列表内容继续拉长，确保外层行高会随着内部文本变高而刷新。
                    """.trimIndent()
                ),
                panelWidth = 900
            )
            messageContainer.add(agent)

            page.updateBubbleMaxWidths()

            kotlin.test.assertEquals(agent.preferredSize.height, agent.maximumSize.height)
            assertTrue(labelsIn(agent).any { it.text?.contains("body width=") == true })
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

    private fun labelsIn(container: Container): List<JLabel> =
        container.components.flatMap { child ->
            when (child) {
                is JLabel -> listOf(child)
                is Container -> labelsIn(child)
                else -> emptyList()
            }
        }

    private fun projectAt(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "isDisposed" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project
}
