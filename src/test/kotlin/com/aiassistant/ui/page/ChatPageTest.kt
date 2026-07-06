package com.aiassistant.ui.page

import com.intellij.openapi.project.Project
import com.aiassistant.ui.MessageBus
import com.aiassistant.ui.chat.ChatViewModel
import java.awt.BorderLayout
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
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
    fun `reasoning updates replace the previous spacer`() {
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

        kotlin.test.assertEquals(2, messageContainer.componentCount)
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
    fun `reasoning block is full viewport width when added`() {
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
        }

        val reasoningBubble = reasoningBubbleField.get(page) as JPanel
        kotlin.test.assertEquals(600, reasoningBubble.preferredSize.width)
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
