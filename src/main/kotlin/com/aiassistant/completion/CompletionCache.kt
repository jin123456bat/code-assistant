package com.aiassistant.completion

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 补全结果缓存。宽松匹配（prefix/suffix 各取前后 200 字符 hash），TTL 60s，LRU 最大 20 条。
 */
class CompletionCache(
    private val ttlMs: Long = 60_000,
    private val maxSize: Int = 20
) {
    private val ttlNanos: Long = ttlMs * 1_000_000L

    data class CacheEntry(
        val candidates: List<String>,
        val createdAtNano: Long = System.nanoTime()
    )

    /** 复用的 SHA-256 MessageDigest 实例，synchronized 保护 */
    private val digest = java.security.MessageDigest.getInstance("SHA-256")

    /** LRU map (access-order)，线程安全由 @Synchronized 保证 */
    private val cache = object : LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    @Synchronized
    fun get(prefix: String, suffix: String): List<String>? {
        val key = makeKey(prefix, suffix)
        val entry = cache[key] ?: return null
        if (System.nanoTime() - entry.createdAtNano > ttlNanos) {
            cache.remove(key)
            return null
        }
        return entry.candidates
    }

    @Synchronized
    fun put(prefix: String, suffix: String, candidates: List<String>) {
        val key = makeKey(prefix, suffix)
        cache[key] = CacheEntry(candidates)
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
    }

    private fun makeKey(prefix: String, suffix: String): String {
        val prefixPart = if (prefix.length > 200) prefix.substring(prefix.length - 200) else prefix
        val suffixPart = if (suffix.length > 200) suffix.substring(0, 200) else suffix
        val raw = "$prefixPart|$suffixPart"
        synchronized(digest) {
            val hash = digest.digest(raw.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
