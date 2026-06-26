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

    private fun visibleTabs(tabBar: TabBar): List<Boolean> =
        tabBar.components.map { it.isVisible }
}
