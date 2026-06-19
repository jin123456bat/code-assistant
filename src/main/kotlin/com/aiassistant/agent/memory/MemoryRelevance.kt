package com.aiassistant.agent.memory

class MemoryRelevance {

    companion object {
        const val MAX_MEMORIES = 5
        const val MAX_CHARS = 2000
    }

    fun match(context: String, memories: List<IndexEntry>): List<IndexEntry> {
        if (memories.isEmpty() || context.isBlank()) return emptyList()
        val contextKeywords = extractKeywords(context)
        val scored: List<Pair<IndexEntry, Int>> = memories.map { entry ->
            val text = "${entry.description} ${entry.name}"
            val score = scoreRelevance(contextKeywords, text)
            Pair(entry, score)
        }.filter { it.second > 0 }
        return scored.sortedByDescending { it.second }.take(MAX_MEMORIES).map { it.first }
    }

    fun extractKeywords(text: String): Set<String> {
        val cleaned = text.lowercase()
            .replace(Regex("""[，。！？、；：""''「」【】《》（）\n\r\t]"""), " ")
            .replace(Regex("""[,.!?;:'\"()\[\]{}<>]"""), " ")
        val words = cleaned.split(Regex("""\s+""")).filter { it.length >= 2 }.filter { it !in STOP_WORDS }.toSet()
        val chineseBigrams = Regex("""[一-鿿]{2}""").findAll(cleaned).map { it.value }.toSet()
        return words + chineseBigrams
    }

    private fun scoreRelevance(keywords: Set<String>, text: String): Int {
        val lower = text.lowercase()
        var score = 0
        for (kw in keywords) {
            if (lower.contains(kw)) score += if (kw.length >= 3) 3 else 1
        }
        return score
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been",
        "in", "on", "at", "to", "for", "of", "and", "or", "but",
        "it", "this", "that", "with", "from", "as", "by",
        "的", "了", "是", "在", "我", "有", "和", "就", "不",
        "人", "都", "一", "一个", "上", "也", "很", "到", "说",
        "要", "去", "你", "会", "着", "没有", "看", "好", "自己",
        "这", "他", "她", "它", "们"
    )
}
