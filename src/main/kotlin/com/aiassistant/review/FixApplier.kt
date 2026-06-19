package com.aiassistant.review

import java.io.File

class FixApplier {
    data class FixResult(val fixed: Int, val skipped: Int, val details: List<String>)

    fun apply(findings: List<Finding>, projectBasePath: String?): FixResult {
        val toFix = findings.filter { it.severity == Severity.CRITICAL || it.confidence >= 8 }
        var fixed = 0
        val details = mutableListOf<String>()

        for (f in toFix) {
            if (f.suggestion.isBlank()) {
                details.add("⚠️ ${f.file}:${f.line} — ${f.title}（无修复建议，跳过）")
                continue
            }
            try {
                val file = File(projectBasePath, f.file)
                if (!file.isFile) {
                    details.add("⚠️ ${f.file} 不存在，跳过")
                    continue
                }
                val content = file.readText(Charsets.UTF_8)
                val lines = content.lines()
                if (f.line < 1 || f.line > lines.size) {
                    details.add("⚠️ ${f.file}:${f.line} 行号越界")
                    continue
                }
                val newContent = content.replace(
                    lines[f.line - 1],
                    "// REVIEW-FIX: ${f.title}\n${lines[f.line - 1]}"
                )
                file.writeText(newContent, Charsets.UTF_8)
                fixed++
                details.add("✅ ${f.file}:${f.line} — ${f.title}")
            } catch (e: Exception) {
                details.add("❌ ${f.file}:${f.line} — ${e.message}")
            }
        }
        return FixResult(fixed, toFix.size - fixed, details)
    }
}
