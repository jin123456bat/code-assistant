package com.aiassistant

import org.junit.Assert.*
import org.junit.Test

class AnthropicAdapterTest {

    private val adapter = AnthropicAdapter()

    @Test
    fun `should build request with tools`() {
        val toolsJson = """[{"name":"git_status","description":"test","input_schema":{"type":"object","properties":{},"additionalProperties":false}}]"""
        val messages = listOf(AnthropicMessage("user", "hello"))
        val body = adapter.buildRequest("sys", messages, toolsJson)

        assertTrue(body.contains("deepseek-v4-pro"))
        assertTrue(body.contains("tools"))
        assertTrue(body.contains("messages"))
        assertTrue(body.contains("tool_choice"))
        assertTrue(body.contains("max_tokens"))
    }

    @Test
    fun `should parse text delta`() {
        val event = adapter.parseSseEvent("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""")
        assertTrue(event is ParsedEvent.TextDelta)
        assertEquals("Hi", (event as ParsedEvent.TextDelta).text)
    }

    @Test
    fun `should parse tool use start`() {
        val event = adapter.parseSseEvent("""{"type":"content_block_start","content_block":{"type":"tool_use","id":"t1","name":"git_status"}}""")
        assertTrue(event is ParsedEvent.ToolUseStart)
        val ts = event as ParsedEvent.ToolUseStart
        assertEquals("t1", ts.id)
        assertEquals("git_status", ts.name)
    }

    @Test
    fun `should parse input json delta`() {
        val event = adapter.parseSseEvent("""{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{}"}}""")
        assertTrue(event is ParsedEvent.InputJsonDelta)
    }

    @Test
    fun `should parse message delta with tool_use`() {
        val event = adapter.parseSseEvent("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        assertTrue(event is ParsedEvent.MessageDelta)
        assertEquals("tool_use", (event as ParsedEvent.MessageDelta).stopReason)
    }

    @Test
    fun `should parse message stop`() {
        val event = adapter.parseSseEvent("""{"type":"message_stop"}""")
        assertTrue(event is ParsedEvent.MessageStop)
    }

    @Test
    fun `should ignore non data lines`() {
        assertNull(adapter.parseSseEvent("event: ping"))
        assertNull(adapter.parseSseEvent(""))
    }

    @Test
    fun `should build tool use message with thinking block`() {
        val msg = AnthropicMessage("assistant", "", toolUseId = "t1", toolName = "git_status", toolInput = "{}")
        val body = adapter.buildRequest("sys", listOf(msg), "[]")
        assertTrue(body.contains("tool_use"))
        assertTrue(body.contains("git_status"))
        assertTrue(body.contains("thinking"))  // DeepSeek V4 requires thinking block in replay
    }

    @Test
    fun `should parse thinking delta`() {
        val event = adapter.parseSseEvent("""{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"Hmm..."}}""")
        assertTrue(event is ParsedEvent.ThinkingDelta)
        assertEquals("Hmm...", (event as ParsedEvent.ThinkingDelta).thinking)
    }

    @Test
    fun `should parse thinking start`() {
        val event = adapter.parseSseEvent("""{"type":"content_block_start","content_block":{"type":"thinking","thinking":""}}""")
        assertTrue(event is ParsedEvent.ThinkingStart)
    }

    @Test
    fun `should parse message start`() {
        val event = adapter.parseSseEvent("""{"type":"message_start","message":{"id":"msg_1","model":"deepseek-v4-flash"}}""")
        assertTrue(event is ParsedEvent.MessageStart)
    }

    @Test
    fun `should build tool result message`() {
        val msg = AnthropicMessage("user", "out", toolCallId = "t1")
        val body = adapter.buildRequest("sys", listOf(msg), "[]")
        assertTrue(body.contains("tool_result"))
        assertTrue(body.contains("t1"))
    }
}
