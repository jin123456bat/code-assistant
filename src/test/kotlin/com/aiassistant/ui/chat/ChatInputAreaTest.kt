package com.aiassistant.ui.chat

import javax.swing.JButton
import kotlin.test.Test
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

    private fun findSendButton(inputArea: ChatInputArea): JButton =
        inputArea.components.filterIsInstance<JButton>().single { it.text == "发送" }
}
