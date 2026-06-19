package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class PermissionAnalyzer {
    fun scan(content: String, filePath: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        val lines = content.lines()
        // 仅检测文件操作 API 中的 ../，避免注释/字符串常量中的误报
        val riskPattern = Regex("""File\s*\(\s*["'][^"']*\.\.[^"']*["']""")
        for ((i, line) in lines.withIndex()) {
            when {
                line.contains("chmod 777") || line.contains("chmod -R 777") ->
                    findings.add(Finding(Severity.CRITICAL, Category.SECURITY, filePath, i+1, "不安全文件权限", "chmod 777 给所有用户完全读写执行权限", "使用更严格的权限如 chmod 755", 9))
                riskPattern.containsMatchIn(line) && !line.contains("canonicalPath") ->
                    findings.add(Finding(Severity.WARNING, Category.SECURITY, filePath, i+1, "路径遍历风险", "检测到 File(..) 中的 ../ 但未使用 canonicalPath 校验", "添加 PathUtils.isInsideProject 路径校验", 7))
            }
        }
        return findings
    }
}
