package com.aiassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Additional edge case tests for DeepSeekAdapter.parseDeltaContent.
 */
class DeepSeekAdapterParseTest {

    private val adapter = DeepSeekAdapter()

    @Test
    fun `should handle Chinese characters in content`() {
        val chunk = """{"choices":[{"delta":{"content":"你好世界"}}]}"""
        assertEquals("你好世界", adapter.parseDeltaContent(chunk))
    }

    @Test
    fun `should handle content with newlines`() {
        val chunk = """{"choices":[{"delta":{"content":"line1\nline2"}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assertEquals("line1\nline2", result)
    }

    @Test
    fun `should handle content with escaped quotes`() {
        val chunk = """{"choices":[{"delta":{"content":"he said \"hello\""}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assertEquals("he said \"hello\"", result)
    }

    @Test
    fun `should handle code block content`() {
        val chunk = """{"choices":[{"delta":{"content":"```php\n<?php echo 'hi';\n```"}}]}"""
        val result = adapter.parseDeltaContent(chunk)
        assert(result!!.contains("```php"))
    }

    @Test
    fun `should handle empty choices array`() {
        val chunk = """{"choices":[]}"""
        assertNull(adapter.parseDeltaContent(chunk))
    }

    @Test
    fun `should handle null delta`() {
        val chunk = """{"choices":[{"delta":null}]}"""
        assertNull(adapter.parseDeltaContent(chunk))
    }

    @Test
    fun `should handle missing choices field`() {
        val chunk = """{"id":"1"}"""
        assertNull(adapter.parseDeltaContent(chunk))
    }
}
