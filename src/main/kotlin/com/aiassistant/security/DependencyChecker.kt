package com.aiassistant.security

import com.aiassistant.review.Finding
import com.aiassistant.review.Severity
import com.aiassistant.review.Category
import java.io.File

class DependencyChecker(private val projectBasePath: String?) {
    fun check(): List<Finding> {
        val base = projectBasePath ?: return emptyList()
        val findings = mutableListOf<Finding>()

        // 扫描所有 build.gradle.kts / build.gradle 文件（支持多模块项目）
        val gradleFiles = File(base).walkTopDown()
            .filter { it.name == "build.gradle.kts" || it.name == "build.gradle" }
            .take(10)  // 限制深度避免扫描过多
            .toList()

        for (gradleFile in gradleFiles) {
            checkGradleFile(gradleFile, findings, base)
        }
        return findings
    }

    private fun checkGradleFile(gradleFile: File, findings: MutableList<Finding>, base: String) {
        val content = gradleFile.readText()
        val relativePath = gradleFile.relativeTo(File(base)).path
        val riskyDeps = mapOf(
            "com.google.code.gson:gson:2.8." to "Gson < 2.8.9 存在反序列化漏洞",
            "log4j:log4j:1." to "Log4j 1.x 已停止维护，含多个 CVE",
        )
        val lines = content.lines()
        for ((i, line) in lines.withIndex()) {
            for ((dep, desc) in riskyDeps) {
                if (line.contains(dep)) findings.add(Finding(Severity.CRITICAL, Category.SECURITY, relativePath, i+1, "依赖漏洞", desc, "请升级到安全版本", 8))
            }
        }
    }
}
