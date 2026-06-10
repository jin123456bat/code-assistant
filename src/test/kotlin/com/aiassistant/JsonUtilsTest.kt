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

    @Test
    fun `literal backslash-n should not become newline`() {
        // JSON 字面值 "\\n" 表示「反斜杠 + 字母 n」，反转义应得到 \n 两个字符，
        // 而不是一个换行符（旧链式 replace 实现的 bug）。
        assertEquals("\\n", JsonUtils.unescapeJson("\\\\n"))
    }

    @Test
    fun `windows path backslashes preserved`() {
        // "C:\\new" (JSON) → C:\new
        assertEquals("C:\\new", JsonUtils.unescapeJson("C:\\\\new"))
    }

    @Test
    fun `unicode escape decoded`() {
        // 你好 → 你好
        assertEquals("你好", JsonUtils.unescapeJson("\\u4f60\\u597d"))
    }

    @Test
    fun `escape then unescape roundtrip with backslash`() {
        val original = "path C:\\dir\\file regex \\d+ tab\there"
        assertEquals(original, JsonUtils.unescapeJson(JsonUtils.escapeJson(original)))
    }
}
