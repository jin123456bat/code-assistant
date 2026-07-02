package com.aiassistant.ui.page

import com.intellij.openapi.project.Project
import com.aiassistant.ui.MessageBus
import java.awt.BorderLayout
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
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
