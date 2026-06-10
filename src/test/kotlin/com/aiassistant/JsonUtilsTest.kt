package com.aiassistant

import com.aiassistant.shared.JsonUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonUtilsTest {

    @Test
    fun `should escape newlines`() {
        assertEquals("\\n", JsonUtils.escapeJson("\n"))
    }

    @Test
    fun `should escape quotes`() {
        assertEquals("\\\"", JsonUtils.escapeJson("\""))
    }

    @Test
    fun `should escape backslash`() {
        assertEquals("\\\\", JsonUtils.escapeJson("\\"))
    }

    @Test
    fun `should unescape newlines`() {
        assertEquals("\n", JsonUtils.unescapeJson("\\n"))
    }

    @Test
    fun `should unescape quotes`() {
        assertEquals("\"", JsonUtils.unescapeJson("\\\""))
    }

    @Test
    fun `should roundtrip complex string`() {
        val original = "Hello\nWorld \"quoted\"\nLine3"
        val escaped = JsonUtils.escapeJson(original)
        val unescaped = JsonUtils.unescapeJson(escaped)
        assertEquals(original, unescaped)
    }
}
