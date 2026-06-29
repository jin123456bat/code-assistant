package com.aiassistant.ui.chat

import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatInputAreaTest {

    @Test
    fun `disables send button with input`() {
        val inputArea = ChatInputArea(onSend = {})
        val sendButton = findSendButton(inputArea)

        inputArea.setInputEnabled(false)
        assertFalse(sendButton.isEnabled)

        inputArea.setInputEnabled(true)
        assertTrue(sendButton.isEnabled)
    }

    @Test
    fun `shows add file button`() {
        val inputArea = ChatInputArea(onSend = {})

        assertTrue(buttonsIn(inputArea).any { it.text == "+" })
    }

    @Test
    fun `sends selected file tag as file reference`() {
        var sent = ""
        val inputArea = ChatInputArea(onSend = { sent = it })

        ChatInputArea::class.java
            .getDeclaredMethod("addFileReference", String::class.java)
            .apply { isAccessible = true }
            .invoke(inputArea, "@README.md")

        findSendButton(inputArea).doClick()

        assertContains(sent, "@README.md")
        assertTrue(labelsIn(inputArea).none { it.text?.contains("README.md") == true })
    }

    @Test
    fun `shows and clears selection tag`() {
        val inputArea = ChatInputArea(onSend = {})

        inputArea.setSelectionReference(fileName = "UserService.kt", lineRange = "40-60")
        assertTrue(labelsIn(inputArea).any { it.text?.contains("UserService.kt:40-60") == true })

        inputArea.setSelectionReference(fileName = null)
        assertTrue(labelsIn(inputArea).none { it.text?.contains("UserService.kt:40-60") == true })
    }

    private fun findSendButton(inputArea: ChatInputArea): JButton =
        buttonsIn(inputArea).single { it.text == "发送" }

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
}
