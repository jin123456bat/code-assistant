package com.aiassistant

import com.aiassistant.agent.AgentMessage
import org.junit.Assert.*
import org.junit.Test

class ChatViewModelTest {

    @Test
    fun `should create view model with empty messages`() {
        val viewModel = ChatViewModel()
        assertTrue(viewModel.messages.isEmpty())
        assertFalse(viewModel.isStreaming)
        assertTrue(viewModel.streamingContent.isEmpty())
    }

    @Test
    fun `should not send empty message`() {
        val viewModel = ChatViewModel()
        viewModel.sendMessage("test-key", "")
        assertTrue(viewModel.messages.isEmpty())
    }

    @Test
    fun `should not send blank message`() {
        val viewModel = ChatViewModel()
        viewModel.sendMessage("test-key", "   ")
        assertTrue(viewModel.messages.isEmpty())
    }

    @Test
    fun `should add user message when sending`() {
        val viewModel = ChatViewModel()
        viewModel.sendMessage("test-key", "Hello")
        assertEquals(1, viewModel.messages.size)
        assertEquals("user", viewModel.messages[0].role)
        assertEquals("Hello", viewModel.messages[0].content)
    }

    @Test
    fun `should not allow concurrent sends`() {
        val viewModel = ChatViewModel()
        viewModel.sendMessage("test-key", "First")
        viewModel.sendMessage("test-key", "Second")
        assertEquals(1, viewModel.messages.size)
    }

    @Test
    fun `should clear conversation`() {
        val viewModel = ChatViewModel()
        viewModel.sendMessage("test-key", "Hello")
        viewModel.clearConversation()
        assertTrue(viewModel.messages.isEmpty())
        assertTrue(viewModel.streamingContent.isEmpty())
        assertFalse(viewModel.isRateLimited)
    }

    @Test
    fun `should register and invoke callbacks`() {
        val viewModel = ChatViewModel()
        var messagesChanged = false
        var streamingStateChanged = false
        var errorReceived: String? = null
        var streamingUpdate: String? = null

        viewModel.onMessagesChanged = { messagesChanged = true }
        viewModel.onStreamingStateChanged = { streamingStateChanged = true }
        viewModel.onError = { errorReceived = it }
        viewModel.onStreamingUpdate = { streamingUpdate = it }

        assertNotNull(viewModel.onMessagesChanged)
        assertNotNull(viewModel.onStreamingStateChanged)
        assertNotNull(viewModel.onError)
        assertNotNull(viewModel.onStreamingUpdate)
    }

    @Test
    fun `should have AgentMessage type`() {
        val msg = AgentMessage("user", "test")
        assertEquals("user", msg.role)
        assertEquals("test", msg.content)
        assertNull(msg.toolCalls)
    }
}
