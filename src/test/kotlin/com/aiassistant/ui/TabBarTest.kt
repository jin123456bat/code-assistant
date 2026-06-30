package com.aiassistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import javax.swing.JLayeredPane
import javax.swing.JLabel

class TabBarTest {

    @Test
    fun `shows only welcome tab before api key is configured`() {
        val tabBar = TabBar {}

        tabBar.setApiKeyConfigured(false)

        assertEquals(listOf(true, false, false, false, false, false, false), visibleTabs(tabBar))
    }

    @Test
    fun `hides welcome tab after api key is configured`() {
        val tabBar = TabBar {}

        tabBar.setApiKeyConfigured(true)

        assertEquals(listOf(false, true, true, true, true, true, true), visibleTabs(tabBar))
    }

    @Test
    fun `uses compact icon tabs`() {
        val tabBar = TabBar {}

        // Verify 7 tabs exist, each with 44x32 compact icon-only layout (per docs §二)
        assertEquals(7, tabBar.components.size, "TabBar should have 7 tabs")

        tabBar.components.forEach { component ->
            assertEquals(44, component.preferredSize.width, "Tab width should be 44px")
            assertEquals(32, component.preferredSize.height, "Tab height should be 32px")
        }
    }

    @Test
    fun `disabled tab cannot be selected`() {
        val selected = mutableListOf<ChatToolWindow.Page>()
        val tabBar = TabBar { selected.add(it) }

        tabBar.setEnabled(ChatToolWindow.Page.MCP, false)
        tabLabel(tabBar, ChatToolWindow.Page.MCP).dispatchEvent(
            java.awt.event.MouseEvent(
                tabLabel(tabBar, ChatToolWindow.Page.MCP),
                java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                10,
                10,
                1,
                false
            )
        )

        assertEquals(emptyList(), selected)
        assertEquals(false, tabLabel(tabBar, ChatToolWindow.Page.MCP).isEnabled)
    }

    private fun visibleTabs(tabBar: TabBar): List<Boolean> =
        tabBar.components.map { it.isVisible }

    private fun tabLabel(tabBar: TabBar, page: ChatToolWindow.Page): JLabel {
        val index = ChatToolWindow.Page.entries.indexOf(page)
        val wrapper = tabBar.components[index] as JLayeredPane
        return wrapper.components.filterIsInstance<JLabel>().first()
    }
}
