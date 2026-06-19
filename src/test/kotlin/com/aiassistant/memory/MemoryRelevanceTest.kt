package com.aiassistant.memory

import com.aiassistant.agent.memory.IndexEntry
import com.aiassistant.agent.memory.MemoryRelevance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRelevanceTest {

    @Test fun `extractKeywords filters stop words and short tokens`() {
        val relevance = MemoryRelevance()
        val keywords = relevance.extractKeywords("我在使用 Kotlin 开发 IntelliJ 插件")
        assertTrue(keywords.contains("kotlin"))
        assertTrue(keywords.contains("intellij"))
        assertFalse(keywords.contains("在"))
        assertFalse(keywords.contains("我"))
    }

    @Test fun `extractKeywords includes Chinese bigrams`() {
        val relevance = MemoryRelevance()
        val keywords = relevance.extractKeywords("代码审查功能")
        assertTrue(keywords.contains("代码"))
        assertTrue(keywords.contains("审查"))
    }

    @Test fun `match returns empty for blank context`() {
        val relevance = MemoryRelevance()
        val memories = listOf(IndexEntry("test", "desc", "project"))
        assertTrue(relevance.match("", memories).isEmpty())
    }

    @Test fun `match ranks by relevance score`() {
        val relevance = MemoryRelevance()
        val memories = listOf(
            IndexEntry("java-config", "Java 编译配置", "project"),
            IndexEntry("kotlin-style", "Kotlin 代码风格约定", "project"),
            IndexEntry("python-notes", "Python 脚本笔记", "project"),
        )
        val result = relevance.match("Kotlin 的扩展函数怎么写", memories)
        assertTrue(result.isNotEmpty())
        assertEquals("kotlin-style", result.first().name)
    }

    @Test fun `match truncates to MAX_MEMORIES`() {
        val relevance = MemoryRelevance()
        val memories = (1..10).map { IndexEntry("memory-$it", "memory $it description", "project") }
        val result = relevance.match("memory description", memories)
        assertTrue(result.size <= MemoryRelevance.MAX_MEMORIES)
    }
}
