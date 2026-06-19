package com.aiassistant.agent.memory

/**
 * MemoryEngine：记忆模块的入口整合层。
 *
 * 协调 MemoryStore（存储层）和 MemoryRelevance（相关性匹配），
 * 提供带缓存的统一 API，对齐 Claude Code memory 模块的设计。
 */
class MemoryEngine(projectBasePath: String?) {

    val store = MemoryStore(projectBasePath)
    private val relevance = MemoryRelevance()

    @Volatile
    private var cachedIndex: List<IndexEntry>? = null

    /**
     * 根据上下文获取相关记忆：
     * 1. 获取索引
     * 2. 通过 MemoryRelevance 匹配最相关的条目（最多 5 条）
     * 3. 按需读取完整内容
     */
    fun getRelevantMemories(context: String): List<MemoryEntry> {
        val index = getIndex()
        val matched = relevance.match(context, index)
        return matched.mapNotNull { store.read(it.name) }
    }

    /**
     * 获取索引列表，优先返回缓存。
     */
    fun getIndex(): List<IndexEntry> {
        if (cachedIndex == null) {
            cachedIndex = store.list()
        }
        return cachedIndex!!
    }

    /**
     * 使索引缓存失效，下次 getIndex() 会重新从磁盘读取。
     */
    fun invalidateCache() {
        cachedIndex = null
    }

    /**
     * 写入记忆并刷新缓存。
     */
    fun write(entry: MemoryEntry) = store.write(entry).also { invalidateCache() }

    /**
     * 读取单条记忆。
     */
    fun read(name: String) = store.read(name)

    /**
     * 删除记忆并刷新缓存。
     */
    fun delete(name: String) = store.delete(name).also { invalidateCache() }

    /**
     * 列出所有索引条目（不读缓存，直接查磁盘）。
     */
    fun list() = store.list()
}
