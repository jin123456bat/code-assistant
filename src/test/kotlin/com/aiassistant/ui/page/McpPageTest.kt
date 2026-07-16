package com.aiassistant.ui.page

import com.aiassistant.mcp.McpManager
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpPageTest {

    @Test
    fun `server card keeps its content height inside vertical list`() {
        val page = McpPage(projectAt(createTempDirectory().toString()))

        try {
            val card = renderCard(page, errorServer())

            assertEquals(
                card.preferredSize.height,
                card.maximumSize.height,
                "MCP card must not stretch vertically to fill the whole server list"
            )
        } finally {
            page.dispose()
        }
    }

    @Test
    fun `server card keeps details and actions in separate rows`() {
        val page = McpPage(projectAt(createTempDirectory().toString()))

        try {
            val card = renderCard(page, errorServer())
            val layout = card.layout as BorderLayout
            val details = layout.getLayoutComponent(BorderLayout.CENTER)
            val actions = layout.getLayoutComponent(BorderLayout.SOUTH)

            assertNotNull(details, "Server details must remain the main content of the card")
            assertNotNull(actions, "Secondary actions must use their own row")
            assertIs<Container>(actions)
            assertTrue(
                buttonsIn(actions).map { it.text }.containsAll(listOf("测试连接", "查看日志", "编辑", "删除")),
                "The action row must contain all secondary server actions"
            )
            assertTrue(
                actions.preferredSize.width <= NARROW_CARD_CONTENT_WIDTH,
                "The action grid must fit inside a 250px ToolWindow"
            )
        } finally {
            page.dispose()
        }
    }

    @Test
    fun `server list follows viewport width when horizontal scrolling is disabled`() {
        val page = McpPage(projectAt(createTempDirectory().toString()))

        try {
            val scrollPane = componentsIn(page).filterIsInstance<JScrollPane>().single()
            val list = scrollPane.viewport.view

            assertIs<Scrollable>(list)
            assertTrue(list.scrollableTracksViewportWidth)
        } finally {
            page.dispose()
        }
    }

    @Test
    fun `empty state keeps its content height inside vertical list`() {
        val page = McpPage(projectAt(createTempDirectory().toString()))

        try {
            val scrollPane = componentsIn(page).filterIsInstance<JScrollPane>().single()
            val list = scrollPane.viewport.view as Container
            val emptyState = list.components.single() as JPanel

            assertEquals(emptyState.preferredSize.height, emptyState.maximumSize.height)
        } finally {
            page.dispose()
        }
    }

    private fun errorServer(): McpManager.McpServer =
        McpManager.McpServer(
            config = McpManager.McpServerConfig(
                id = "filesystem",
                command = "npx -y @modelcontextprotocol/server-filesystem /a/very/long/project/path"
            ),
            state = McpManager.State.ERROR
        ).apply {
            lastErrorMessage = "Initialization failed"
            registeredToolNames.addAll(listOf("filesystem/read_file", "filesystem/list_directory"))
        }

    private fun renderCard(page: McpPage, server: McpManager.McpServer): JPanel {
        val method = McpPage::class.java.getDeclaredMethod("renderCard", McpManager.McpServer::class.java)
        method.isAccessible = true
        return method.invoke(page, server) as JPanel
    }

    private fun buttonsIn(container: Container): List<JButton> =
        container.components.flatMap { child ->
            when (child) {
                is JButton -> listOf(child)
                is Container -> buttonsIn(child)
                else -> emptyList()
            }
        }

    private fun componentsIn(container: Container): List<java.awt.Component> =
        container.components.flatMap { child ->
            when (child) {
                is Container -> listOf(child) + componentsIn(child)
                else -> listOf(child)
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
                "hashCode" -> basePath.hashCode()
                "equals" -> false
                "toString" -> "TestProject($basePath)"
                else -> null
            }
        } as Project

    private companion object {
        const val NARROW_CARD_CONTENT_WIDTH = 226
    }
}
