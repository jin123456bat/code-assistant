package com.aiassistant.completion

import java.security.MessageDigest
import java.util.LinkedHashMap

/**
 * 补全结果缓存。宽松匹配（prefix/suffix 各取前后 200 字符 hash），TTL 60s，LRU 最大 20 条。
 *
 * 补全拒绝与负反馈：用户拒绝的候选通过 [remove] 立即从缓存中移除，
 * 同时将 SHA-256 key 加入 [rejectedKeys]（内存 LRU，最大 200 条，5 分钟 TTL），
 * 后续 5 分钟内同一位置返回 null。
 */
class CompletionCache(
    private val ttlMs: Long = 60_000,
    private val maxSize: Int = 20
) {
    private val ttlNanos: Long = ttlMs * 1_000_000L
    private val rejectedKeysTtlNanos: Long = 5 * 60_000 * 1_000_000L  // 5 分钟
    private val rejectedKeysMaxSize: Int = 200

    data class CacheEntry(
        val candidates: List<String>,
        val createdAtNano: Long = System.nanoTime()
    )

    /** 拒绝记录条目，带创建时间戳用于 TTL 过期 */
    private data class RejectedEntry(
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

    /**
     * 拒绝 Key 集合（access-order LRU，最大 200 条）。
     * Value 是 RejectedEntry 用于 TTL 过期检查。
     */
    private val rejectedKeys =
        object : LinkedHashMap<String, RejectedEntry>(rejectedKeysMaxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RejectedEntry>?): Boolean {
                return size > rejectedKeysMaxSize
            }
        }

    @Synchronized
    fun get(prefix: String, suffix: String): List<String>? {
        val key = makeKey(prefix, suffix)

        // 检查拒绝列表：在 TTL 内则返回 null
        val rejectedEntry = rejectedKeys[key]
        if (rejectedEntry != null) {
            if (System.nanoTime() - rejectedEntry.createdAtNano < rejectedKeysTtlNanos) {
                return null
            } else {
                // TTL 过期，移除拒绝记录
                rejectedKeys.remove(key)
            }
        }

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
        // 新补全结果覆盖时，清除对应的拒绝标记
        rejectedKeys.remove(key)
        cache[key] = CacheEntry(candidates)
    }

    /**
     * 从缓存中移除指定位置的补全候选（用户拒绝时调用），
     * 并将 SHA-256 key 加入 [rejectedKeys]，后续 5 分钟内同一位置返回 null。
     */
    @Synchronized
    fun remove(prefix: String, suffix: String) {
        val key = makeKey(prefix, suffix)
        cache.remove(key)
        rejectedKeys[key] = RejectedEntry()
    }

    @Synchronized
    fun clearAll() {
        cache.clear()
        rejectedKeys.clear()
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
