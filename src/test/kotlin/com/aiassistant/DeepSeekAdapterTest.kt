package com.aiassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAdapterTest {

    private val adapter = DeepSeekAdapter()

    @Test
    fun `should build request JSON with messages`() {
        val messages = listOf(
            ChatMessage("user", "Hello"),
            ChatMessage("assistant", "Hi there!")
        )
        val json = adapter.buildRequest(messages, stream = true)

        assertTrue(json.contains("\"model\":\"deepseek-chat\""))
        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"content\":\"Hello\""))
        assertTrue(json.contains("\"role\":\"assistant\""))
        assertTrue(json.contains("\"content\":\"Hi there!\""))
    }

    @Test
    fun `should build request with empty messages`() {
        val json = adapter.buildRequest(emptyList(), stream = false)
        assertTrue(json.contains("\"messages\":[]"))
        assertTrue(json.contains("\"stream\":false"))
    }

    @Test
    fun `should build request with non-streaming`() {
        val messages = listOf(ChatMessage("user", "test"))
        val json = adapter.buildRequest(messages, stream = false)
        assertTrue(json.contains("\"stream\":false"))
    }

    @Test
    fun `should parse delta content from SSE chunk`() {
        val chunk = """{"id":"1","choices":[{"index":0,"delta":{"content":"Hello world"}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assertEquals("Hello world", result)
    }

    @Test
    fun `should return null for empty delta`() {
        val chunk = """{"id":"1","choices":[{"index":0,"delta":{}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assertNull(result)
    }

    @Test
    fun `should return null for invalid JSON`() {
        val result = adapter.parseDeltaContent("not json")
        assertNull(result)
    }

    @Test
    fun `should return null for empty string`() {
        val result = adapter.parseDeltaContent("")
        assertNull(result)
    }

    @Test
    fun `should return null for missing content field`() {
        val chunk = """{"id":"1","choices":[{"index":0,"delta":{"role":"assistant"}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assertNull(result) // no content field in delta
    }

    @Test
    fun `should escape special characters in JSON`() {
        val messages = listOf(ChatMessage("user", "line1\nline2\twith\"quotes"))
        val json = adapter.buildRequest(messages)
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        assertTrue(json.contains("\\\""))
    }

    @Test
    fun `should use default endpoint and model`() {
        assertEquals(DeepSeekAdapter.DEFAULT_ENDPOINT, adapter.endpoint)
    }

    @Test
    fun `should build multi-message conversation`() {
        val messages = listOf(
            ChatMessage("system", "You are a helpful assistant"),
            ChatMessage("user", "What is Kotlin?"),
            ChatMessage("assistant", "Kotlin is a programming language."),
            ChatMessage("user", "Tell me more")
        )
        val json = adapter.buildRequest(messages)
        assertTrue(json.contains("\"system\""))
        assertTrue(json.contains("\"user\""))
        assertTrue(json.contains("\"assistant\""))
    }
}
