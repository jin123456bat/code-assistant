package com.aiassistant

import com.aiassistant.agent.AgentChatMessage
import com.aiassistant.agent.ToolCallData
import com.aiassistant.agent.toApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatMessageTest {

    @Test
    fun `should serialize user message to api json`() {
        val msg = AgentChatMessage(role = "user", content = "Hello")
        val json = msg.toApiJson()
        assertEquals("""{"role":"user","content":"Hello"}""", json)
    }

    @Test
    fun `should serialize assistant message to api json`() {
        val msg = AgentChatMessage(role = "assistant", content = "World")
        val json = msg.toApiJson()
        assertTrue(json.contains(""""role":"assistant""""))
    }

    @Test
    fun `should serialize tool message to api json`() {
        val msg = AgentChatMessage(role = "tool", content = "result", toolCallId = "call_123")
        val json = msg.toApiJson()
        assertTrue(json.contains(""""role":"tool""""))
        assertTrue(json.contains(""""tool_call_id":"call_123""""))
    }

    @Test
    fun `should serialize assistant with tool calls`() {
        val msg = AgentChatMessage(
            role = "assistant",
            content = "",
            toolCalls = listOf(ToolCallData(id = "1", name = "search", arguments = """{"q":"test"}"""))
        )
        val json = msg.toApiJson()
        assertTrue(json.contains(""""tool_calls":["""))
        assertTrue(json.contains(""""name":"search""""))
    }

    @Test
    fun `should escape special chars in content`() {
        val msg = AgentChatMessage(role = "user", content = "Line1\nLine2\"quote\"")
        val json = msg.toApiJson()
        assertTrue(json.contains("""\n"""))
        assertTrue(json.contains("""\""""))
    }
}
