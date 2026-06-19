package com.aiassistant.review

class ReviewAnalyzer {

    fun buildPrompt(fileChanges: List<FileChange>): String {
        return buildString {
            appendLine("你是一位资深代码审查员。请审查以下 git diff，从四个维度分析：")
            appendLine()
            appendLine("1. **正确性:** 空指针风险、竞态条件、边界条件缺陷、逻辑错误")
            appendLine("2. **简化性:** DRY 违规、魔法数字、过长方法、无用代码、可复用机会")
            appendLine("3. **效率:** N+1 查询、不必要的内存分配、可优化路径")
            appendLine("4. **安全性:** 注入向量、密钥硬编码、不安全 API 调用")
            appendLine()
            appendLine("输出 JSON 数组（不要其他文字）：")
            appendLine("""[{"severity":"CRITICAL|WARNING|INFO","category":"BUG|SIMPLIFY|PERF|SECURITY","file":"路径","line":行号,"title":"标题","description":"详细说明","suggestion":"建议修复代码","confidence":85}]""")
            appendLine()
            appendLine("--- DIFF ---")
            for (change in fileChanges.filter { !it.isBinary }) {
                appendLine("\n## ${change.path} (${change.status})")
                for (hunk in change.hunks) {
                    appendLine("@@ ${hunk.oldStart},${hunk.oldCount} → ${hunk.newStart},${hunk.newCount}")
                    for (line in hunk.lines.take(50)) appendLine(line)
                }
            }
            appendLine()
            appendLine("---")
            appendLine("JSON:")
        }
    }

    fun parseResult(text: String): List<Finding> {
        return try {
            val jsonStart = text.indexOf('[')
            val jsonEnd = text.lastIndexOf(']')
            if (jsonStart < 0 || jsonEnd < 0) return emptyList()
            val json = text.substring(jsonStart, jsonEnd + 1)
            val gson = com.google.gson.Gson()
            val listType = com.google.gson.reflect.TypeToken.getParameterized(
                MutableList::class.java, Map::class.java
            ).type
            val arr: List<Map<String, Any>> = gson.fromJson(json, listType)
            arr.mapNotNull { item ->
                try {
                    Finding(
                        severity = parseSeverity(item["severity"]?.toString()),
                        category = parseCategory(item["category"]?.toString()),
                        file = item["file"]?.toString() ?: "",
                        line = (item["line"] as? Number)?.toInt() ?: 0,
                        title = item["title"]?.toString() ?: "",
                        description = item["description"]?.toString() ?: "",
                        suggestion = item["suggestion"]?.toString() ?: "",
                        confidence = (item["confidence"] as? Number)?.toInt() ?: 5
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseSeverity(s: String?): Severity = when (s?.uppercase()) {
        "CRITICAL" -> Severity.CRITICAL; "WARNING" -> Severity.WARNING; else -> Severity.INFO
    }
    private fun parseCategory(s: String?): Category = when (s?.uppercase()) {
        "BUG" -> Category.BUG; "SIMPLIFY" -> Category.SIMPLIFY; "PERF" -> Category.PERF; "SECURITY" -> Category.SECURITY; else -> Category.BUG
    }
}
