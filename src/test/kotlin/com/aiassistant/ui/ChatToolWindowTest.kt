package com.aiassistant.ui

import kotlin.test.Test
import kotlin.test.assertFalse

class ChatToolWindowTest {

    @Test
    fun `does not eagerly create secondary pages`() {
        val source = java.io.File("src/main/kotlin/com/aiassistant/ui/ChatToolWindow.kt").readText()

        assertFalse(source.contains("private val sessionsPage ="))
        assertFalse(source.contains("private val tokenUsagePage ="))
        assertFalse(source.contains("private val mcpPage ="))
        assertFalse(source.contains("private val skillsPage ="))
        assertFalse(source.contains("private val settingsPage ="))
        assertFalse(source.contains("pages.add(sessionsPage"))
        assertFalse(source.contains("pages.add(tokenUsagePage"))
        assertFalse(source.contains("pages.add(mcpPage"))
        assertFalse(source.contains("pages.add(skillsPage"))
        assertFalse(source.contains("pages.add(settingsPage"))
    }
}
