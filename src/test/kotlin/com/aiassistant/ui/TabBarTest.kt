package com.aiassistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun visibleTabs(tabBar: TabBar): List<Boolean> =
        tabBar.components.map { it.isVisible }
}
