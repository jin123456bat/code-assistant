package com.aiassistant

import com.aiassistant.agent.ToolCallData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAdapterToolCallTest {

    private val adapter = DeepSeekAdapter()

    @Test
    fun `should parse tool calls from response`() {
        val json = """
            {"choices":[{"message":{"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"q\":\"test\"}"}},
              {"id":"call_2","type":"function","function":{"name":"read","arguments":"{\"path\":\"src/a.kt\"}"}}
            ]}}]}
        """.trimIndent()
        val calls = adapter.parseToolCalls(json)
        assertEquals(2, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("search", calls[0].name)
        assertTrue(calls[0].arguments.contains("test"))
        assertEquals("call_2", calls[1].id)
    }

    @Test
    fun `should return empty list when no tool calls`() {
        val json = """{"choices":[{"message":{"content":"Hello"}}]}"""
        val calls = adapter.parseToolCalls(json)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `should parse finish reason`() {
        assertEquals("tool_calls", adapter.parseFinishReason(""" "finish_reason":"tool_calls" """))
        assertEquals("stop", adapter.parseFinishReason(""" "finish_reason":"stop" """))
        assertNull(adapter.parseFinishReason(""" "content":"hello" """))
    }

    @Test
    fun `should parse stream tool call delta`() {
        val delta = adapter.parseStreamToolCallDelta(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"tc1","type":"function","function":{"name":"search","arguments":"s"}}]}}]}"""
        )
        assertEquals(0, delta?.index)
        assertEquals("tc1", delta?.id)
        assertEquals("search", delta?.name)
        assertEquals("s", delta?.arguments)
    }

    @Test
    fun `should parse stream tool call with null delta`() {
        val delta = adapter.parseStreamToolCallDelta("""{"content":"hello"}""")
        assertNull(delta)
    }
}
