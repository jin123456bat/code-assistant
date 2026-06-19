package com.aiassistant.review

import org.junit.Test
import org.junit.Assert.*

class CommentFormatterTest {

    private val formatter = CommentFormatter()

    @Test
    fun `toGitHub formats findings correctly`() {
        val findings = listOf(
            Finding(Severity.CRITICAL, Category.BUG, "Foo.kt", 42, "空指针", "可能NPE", "加null检查", 9)
        )
        val result = formatter.toGitHub(findings)
        assertTrue(result.contains("CRITICAL"))
        assertTrue(result.contains("空指针"))
        assertTrue(result.contains("加null检查"))
    }

    @Test
    fun `toGitHub returns empty for empty list`() {
        val result = formatter.toGitHub(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `toGitHub includes suggestion block`() {
        val findings = listOf(
            Finding(Severity.WARNING, Category.SIMPLIFY, "Bar.kt", 10, "重复代码", "可提取", "提取公共方法", 7)
        )
        val result = formatter.toGitHub(findings)
        assertTrue(result.contains("```suggestion"))
        assertTrue(result.contains("提取公共方法"))
    }

    @Test
    fun `toGitHub skips suggestion when blank`() {
        val findings = listOf(
            Finding(Severity.INFO, Category.PERF, "Baz.kt", 1, "优化", "可缓存", "", 4)
        )
        val result = formatter.toGitHub(findings)
        assertFalse(result.contains("```suggestion"))
    }
}
