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

    @Test
    fun `token usage page wires row selection to chat restore`() {
        val source = java.io.File("src/main/kotlin/com/aiassistant/ui/ChatToolWindow.kt").readText()

        kotlin.test.assertTrue(source.contains("onSessionSelected = { id -> replaceChatPage(id) }"))
    }

    @Test
    fun `tool window content owns disposable panel`() {
        val source =
            java.io.File("src/main/kotlin/com/aiassistant/ui/ChatToolWindowFactory.kt").readText()

        kotlin.test.assertTrue(source.contains("content.setDisposer(panel)"))
    }
}
