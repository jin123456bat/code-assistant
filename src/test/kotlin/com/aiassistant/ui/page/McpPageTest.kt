package com.aiassistant.ui.page

import com.aiassistant.mcp.McpManager
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.JButton
import javax.swing.JLabel
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
    fun `server cards stay inside narrow and wide tool windows`() {
        val page = McpPage(projectAt(createTempDirectory().toString()))

        try {
            CARD_STATES.forEach { state ->
                NARROW_AND_WIDE_WIDTHS.forEach { width ->
                    val card = renderCard(page, server(state)).apply {
                        setSize(width, preferredSize.height)
                        doLayoutRecursively()
                    }

                    assertChildrenStayInside(card, "$state card at ${width}px")
                    val details = (card.layout as BorderLayout)
                        .getLayoutComponent(BorderLayout.CENTER) as Container
                    val commandLabel = componentsIn(details)
                        .filterIsInstance<JLabel>()
                        .single { it.toolTipText?.startsWith("command: ") == true }
                    if (width == NARROW_AND_WIDE_WIDTHS.first()) {
                        assertTrue(
                            commandLabel.text.endsWith("…"),
                            "Long command should be visibly elided for $state at ${width}px"
                        )
                    }
                    assertTrue(
                        commandLabel.toolTipText.contains("@modelcontextprotocol/server-filesystem"),
                        "The full command must remain available in the tooltip"
                    )
                }
            }
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
            NARROW_AND_WIDE_WIDTHS.forEach { width ->
                emptyState.setSize(width, emptyState.preferredSize.height)
                emptyState.doLayoutRecursively()
                assertChildrenStayInside(emptyState, "empty state at ${width}px")
            }
        } finally {
            page.dispose()
        }
    }

    private fun errorServer(): McpManager.McpServer = server(McpManager.State.ERROR)

    private fun server(state: McpManager.State): McpManager.McpServer =
        McpManager.McpServer(
            config = McpManager.McpServerConfig(
                id = "filesystem",
                command = "npx -y @modelcontextprotocol/server-filesystem /a/very/long/project/path"
            ),
            state = state
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

    private fun Container.doLayoutRecursively() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.doLayoutRecursively() }
    }

    private fun assertChildrenStayInside(container: Container, context: String) {
        container.components.filter { it.isVisible }.forEach { child ->
            assertTrue(child.x >= 0, "$context: ${child.javaClass.simpleName} starts before its parent")
            assertTrue(
                child.x + child.width <= container.width,
                "$context: ${child.javaClass.simpleName} extends past its parent"
            )
            if (child is Container) assertChildrenStayInside(child, context)
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
        val NARROW_AND_WIDE_WIDTHS = listOf(250, 350, 500)
        val CARD_STATES = listOf(
            McpManager.State.CONFIGURED,
            McpManager.State.ERROR,
            McpManager.State.CRASHED
        )
    }
}
