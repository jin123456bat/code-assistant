package com.aiassistant.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatInputAreaSourceTest {

    @Test
    fun `popup uses screen coordinates instead of relative coordinates`() {
        val source =
            java.io.File("src/main/kotlin/com/aiassistant/ui/chat/ChatInputArea.kt").readText()

        assertTrue(source.contains("inputScrollPane.locationOnScreen"))
        assertFalse(source.contains("getPopup(inputScrollPane, scrollPane, 0, y)"))
    }
}
