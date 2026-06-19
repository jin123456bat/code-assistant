package com.aiassistant.agent

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import java.io.File

/**
 * DiagnosticFeedback: Write/Edit 后收集 IntelliJ Inspection 诊断问题。
 * 将 IDE 内置代码检测结果注入 Agent 上下文，帮助 LLM 发现并修复代码问题。
 */
object DiagnosticFeedback {

    /** 单文件诊断结果最大返回条数 */
    const val MAX_DIAGNOSTICS = 10

    /**
     * 收集指定文件的 Inspection 诊断问题。
     * @return Markdown 格式诊断报告，无问题时返回 null
     */
    fun collect(project: Project, filePath: String): String? {
        return try {
            val basePath = project.basePath ?: return null
            val fullPath = if (File(filePath).isAbsolute) filePath else "$basePath/$filePath"
            val vf = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: return null
            if (!vf.isValid || vf.isDirectory) return null

            var result: HighlightResult? = null
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return@invokeAndWait
                    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(doc) ?: return@invokeAndWait
                    result = collectHighlights(psiFile, doc)
                } catch (_: Exception) {
                    // 诊断收集失败不阻塞工具执行
                }
            }

            val diagnostics = result ?: return null
            if (diagnostics.items.isEmpty()) return null

            buildString {
                appendLine("## 代码诊断")
                appendLine("`${diagnostics.fileName}` 的 IDE Inspection 结果：")
                appendLine()
                diagnostics.items.forEach { (severity, line, message) ->
                    val icon = when (severity) {
                        "ERROR" -> "🔴"    // 红色圆
                        else -> "🟡"        // 黄色圆
                    }
                    appendLine("- $icon line $line: $message")
                }
                if (diagnostics.truncated) {
                    appendLine("- ... (诊断结果已截断，共 ${diagnostics.totalCount} 条)")
                }
                appendLine()
                appendLine("请修复以上问题后继续。")
            }
        } catch (_: Exception) {
            // 整个诊断流程兜底保护，确保永不影响主流程
            null
        }
    }

    /**
     * 收集 highlights。
     *
     * 方法 A: 通过 DaemonCodeAnalyzerImpl.getHighlights（public static）获取已计算的 WARNING/ERROR 级 highlights。
     * 方法 B (fallback): PSI 递归遍历收集 PsiErrorElement（语法错误）。
     */
    private fun collectHighlights(psiFile: com.intellij.psi.PsiFile, doc: Document): HighlightResult {
        val items = mutableListOf<Triple<String, Int, String>>()
        var totalCount = 0

        try {
            // 方法 A: DaemonCodeAnalyzerImpl.getHighlights(Document, HighlightSeverity, Project)
            val warnings = DaemonCodeAnalyzerImpl.getHighlights(doc, HighlightSeverity.WARNING, psiFile.project)
            val errors = DaemonCodeAnalyzerImpl.getHighlights(doc, HighlightSeverity.ERROR, psiFile.project)
            val allHighlights = errors + warnings
            totalCount = allHighlights.size

            for (info in allHighlights.take(MAX_DIAGNOSTICS)) {
                val severity = when (info.getSeverity()) {
                    HighlightSeverity.ERROR -> "ERROR"
                    else -> "WARNING"
                }
                val line = doc.getLineNumber(info.startOffset) + 1
                val message = info.description?.take(200) ?: "未描述问题"
                items.add(Triple(severity, line, message))
            }
        } catch (_: Exception) {
            // 方法 A 失败，尝试方法 B
            try {
                val errorElements = mutableListOf<PsiErrorElement>()
                psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitErrorElement(element: PsiErrorElement) {
                        errorElements.add(element)
                        super.visitErrorElement(element)
                    }
                })
                totalCount = errorElements.size

                for (error in errorElements.take(MAX_DIAGNOSTICS)) {
                    val offset = error.textOffset
                    val line = doc.getLineNumber(offset) + 1
                    val message = error.errorDescription.take(200)
                    items.add(Triple("ERROR", line, message))
                }
            } catch (_: Exception) {
                // 两种方式均失败，返回空结果
            }
        }

        return HighlightResult(
            fileName = psiFile.name,
            items = items,
            truncated = totalCount > MAX_DIAGNOSTICS,
            totalCount = totalCount
        )
    }

    private data class HighlightResult(
        val fileName: String,
        val items: List<Triple<String, Int, String>>,
        val truncated: Boolean,
        val totalCount: Int
    )
}
