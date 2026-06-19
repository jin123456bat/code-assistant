package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class InjectionScanner {
    private val patterns = listOf(
        Regex("ProcessBuilder\\s*\\(\\s*\"[^\"]*\\$\\{") to "命令注入风险",
        Regex("/bin/bash\\s+-c") to "Shell 命令执行",
        Regex("Runtime\\.getRuntime\\(\\)\\.exec") to "Runtime.exec 命令执行",
        Regex("String\\.format\\s*\\(.*SELECT.*\\+") to "SQL 拼接风险",
        Regex("\\.innerHTML\\s*=") to "XSS 风险",
    )

    fun scan(content: String, filePath: String): List<Finding> {
        val lines = content.lines()
        val findings = mutableListOf<Finding>()
        for ((i, line) in lines.withIndex()) {
            for ((pattern, desc) in patterns) {
                if (pattern.containsMatchIn(line)) {
                    findings.add(Finding(Severity.WARNING, Category.SECURITY, filePath, i + 1, desc, "检测到第${i+1}行存在潜在$desc", "", 7))
                }
            }
        }
        return findings
    }
}
