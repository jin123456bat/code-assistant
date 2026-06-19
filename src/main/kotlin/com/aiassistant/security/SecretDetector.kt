package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category

class SecretDetector {
    private val secretPatterns = listOf(
        Regex("""(apiKey|api_key|apikey)\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "疑似 API Key 硬编码",
        Regex("""(password|passwd)\s*[:=]\s*["'][^"']+["']""", RegexOption.IGNORE_CASE) to "疑似密码硬编码",
        Regex("""(token|secret)\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "疑似 Token 硬编码",
        Regex("""BEGIN\s+(RSA|EC|DSA|OPENSSH|ENCRYPTED)?\s*PRIVATE\s+KEY""") to "私钥硬编码",
    )

    fun scan(content: String, filePath: String): List<Finding> {
        val lines = content.lines()
        val findings = mutableListOf<Finding>()
        for ((i, line) in lines.withIndex()) {
            for ((pattern, desc) in secretPatterns) {
                if (pattern.containsMatchIn(line)) {
                    findings.add(Finding(Severity.CRITICAL, Category.SECURITY, filePath, i + 1, desc, "第${i+1}行检测到$desc，请使用环境变量或密钥管理服务", "移除硬编码，改用 PasswordSafe/环境变量", 9))
                }
            }
        }
        return findings
    }
}
