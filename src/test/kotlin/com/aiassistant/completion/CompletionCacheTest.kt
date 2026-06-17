package com.aiassistant.completion

import org.junit.Assert.*
import org.junit.Test

class CompletionCacheTest {
    @Test
    fun `should return cached value`() {
        val cache = CompletionCache()
        val candidates = listOf("line1", "line2")
        cache.put("prefix", "suffix", candidates)
        val result = cache.get("prefix", "suffix")
        assertNotNull(result)
        assertEquals(candidates, result)
    }

    @Test
    fun `should return null on cache miss`() {
        val cache = CompletionCache()
        val result = cache.get("unknown", "unknown")
        assertNull(result)
    }

    @Test
    fun `should evict by LRU`() {
        val cache = CompletionCache(ttlMs = 60000, maxSize = 3)
        for (i in 1..5) {
            cache.put("prefix$i", "suffix", listOf("line$i"))
        }
        assertNull(cache.get("prefix1", "suffix"))
        assertNotNull(cache.get("prefix5", "suffix"))
    }
}
