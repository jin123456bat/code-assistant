package com.aiassistant.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRefTest {

    @Test
    fun `简单路径 displayName 格式正确`() {
        val ref = FileRef(path = "src/main/kotlin/User.kt")
        assertEquals("📎 User.kt", ref.displayName)
    }

    @Test
    fun `带行号的 displayName 格式正确`() {
        val ref = FileRef(path = "src/main/kotlin/Service.kt", lines = "40-60")
        assertEquals("📎 Service.kt:40-60", ref.displayName)
    }

    @Test
    fun `selectionRef 包含 content 内容`() {
        val ref = FileRef(
            path = "src/main/kotlin/App.kt",
            lines = "10-25",
            content = "fun main() {\n    println(\"Hello\")\n}"
        )
        assertEquals("src/main/kotlin/App.kt", ref.path)
        assertEquals("10-25", ref.lines)
        assertNotNull(ref.content)
        assertTrue(ref.content!!.contains("println"))
    }

    @Test
    fun `manualRef 不含 lines 和 content`() {
        val ref = FileRef(path = "build.gradle.kts")
        assertNull(ref.lines)
        assertNull(ref.content)
        assertEquals("📎 build.gradle.kts", ref.displayName)
    }

    @Test
    fun `equals 比较基于所有字段`() {
        val a = FileRef(path = "a.kt", lines = "1-2", content = "hello")
        val b = FileRef(path = "a.kt", lines = "1-2", content = "hello")
        val c = FileRef(path = "a.kt", lines = "3-4", content = "hello")

        assertEquals(a, b, "相同字段的 FileRef 应相等")
        assertEquals(a.hashCode(), b.hashCode(), "相同字段的 FileRef hashCode 应一致")
        // 不同 lines 应产生不同对象
        assertTrue(a != c || a.hashCode() != c.hashCode())
    }
}
