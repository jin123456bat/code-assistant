package com.aiassistant.commands

import com.aiassistant.review.ReviewEngine
import com.aiassistant.review.Severity
import com.aiassistant.review.Finding
import com.aiassistant.review.DiffCollector
import com.aiassistant.security.SecurityReviewEngine
import com.aiassistant.ui.ReviewAnnotationGutter

class ReviewCommands(private val projectBasePath: String?, private val getApiKey: () -> String?) {

    private val reviewEngine = ReviewEngine(projectBasePath)
    private val securityEngine = SecurityReviewEngine(projectBasePath)
    private val testRunner = TestRunner(projectBasePath)

    fun diffAction(): String {
        val stat = reviewEngine.getDiffStat()
        return if (stat.isBlank()) "📊 无变更" else buildString {
            appendLine("📊 **变更摘要**\n")
            appendLine("```")
            appendLine(stat)
            appendLine("```")
        }
    }

    fun reviewAction(fixMode: Boolean = false, commentMode: Boolean = false): String {
        val apiKey = getApiKey() ?: return "❌ 请先配置 API Key"
        val result = reviewEngine.review(apiKey)
            ?: return "📋 无变更可审查"

        if (fixMode) {
            val prompt = reviewEngine.fixApplier.buildPrompt(result.findings)
            if (prompt != null) {
                return buildString {
                    appendLine(renderResult(result.findings, result.score, result.totalFiles))
                    appendLine()
                    appendLine("## --fix 自动修复")
                    appendLine("已将 ${result.findings.count { it.severity == Severity.CRITICAL || it.confidence >= 8 }} 条高置信度问题发送给 Agent 自动修复。")
                    appendLine()
                    appendLine(prompt)
                }
            } else {
                return buildString {
                    appendLine(renderResult(result.findings, result.score, result.totalFiles))
                    appendLine()
                    appendLine("## --fix")
                    appendLine("无需修复（无 CRITICAL 或高置信度问题）")
                }
            }
        }
        if (commentMode) return reviewEngine.commentFormatter.toGitHub(result.findings)
        return renderResult(result.findings, result.score, result.totalFiles)
    }

    private fun renderResult(findings: List<Finding>, score: Int, totalFiles: Int): String {
        // 将审查结果写入 gutter，在编辑器行号旁显示标记
        ReviewAnnotationGutter.setFindings(projectBasePath, findings)
        return buildString {
            appendLine("## 📋 代码审查报告")
            appendLine("**评分:** $score/100 | **文件:** $totalFiles | **发现问题:** ${findings.size}")
            appendLine()
            if (findings.isEmpty()) { appendLine("✅ 未发现问题"); return@buildString }
            for (severity in listOf(Severity.CRITICAL, Severity.WARNING, Severity.INFO)) {
                val group = findings.filter { it.severity == severity }
                if (group.isEmpty()) continue
                val icon = when (severity) { Severity.CRITICAL -> "🔴"; Severity.WARNING -> "🟡"; else -> "🔵" }
                appendLine("### $icon ${severity.name} (${group.size})")
                group.forEach { f ->
                    appendLine("- **${f.title}** `${f.file}:${f.line}` (${f.category}, ${f.confidence}/10)")
                    appendLine("  ${f.description}")
                    if (f.suggestion.isNotBlank()) appendLine("  💡 `${f.suggestion.take(80)}`")
                }
                appendLine()
            }
        }
    }

    fun securityReviewAction(): String {
        val collector = DiffCollector(projectBasePath)
        val diff = collector.collectBranchDiff()?.takeIf { it.isNotBlank() }
            ?: return "📋 无变更可审查"
        val fileChanges = collector.parse(diff)
        val fileContents = mutableMapOf<String, String>()
        for (fc in fileChanges.filter { !it.isBinary }) {
            val file = java.io.File(projectBasePath, fc.path)
            if (file.isFile) fileContents[fc.path] = file.readText(Charsets.UTF_8)
        }
        val report = securityEngine.analyze(fileContents)
        // 将安全审查结果写入 gutter，在编辑器行号旁显示标记
        ReviewAnnotationGutter.setFindings(projectBasePath, report.findings)
        return buildString {
            appendLine("## 🔒 安全审查报告")
            appendLine("**评分:** ${report.score}/100 | **维度:** ${report.dimensionsCovered.joinToString(", ")} | **发现问题:** ${report.findings.size}")
            appendLine()
            report.findings.groupBy { it.severity }.forEach { (severity, group) ->
                appendLine("### ${severity.name} (${group.size})")
                group.forEach { f ->
                    appendLine("- **${f.title}** `${f.file}:${f.line}`")
                    appendLine("  ${f.description}")
                }
                appendLine()
            }
        }
    }

    fun testAction(filter: String? = null): String {
        val result = testRunner.run(filter)
        return buildString {
            appendLine(result.summary)
            for (f in result.failures) {
                appendLine("\n### ❌ ${f.testName}")
                appendLine("```")
                appendLine(f.errorMessage.take(500))
                appendLine("```")
            }
            if (result.failures.isNotEmpty()) appendLine("\n💡 使用 `/fix` 让 AI 分析并修复失败的测试")
        }
    }

    fun fixAction(): String? {
        return testRunner.buildFixPrompt()
    }
}
