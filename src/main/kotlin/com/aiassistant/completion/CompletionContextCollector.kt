package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

/**
 * 补全上下文数据结构。
 * - prefix: 光标前的纯文本
 * - suffix: 光标后的纯文本
 * - language: 文件语言标识
 * - fileName: 文件名
 * - smartContext: PSI 增强上下文（import/namespace/父元素等），可能为 null
 */
data class CompletionContext(
    val prefix: String,
    val suffix: String,
    val language: String,
    val fileName: String,
    val smartContext: String?
)

/**
 * 补全上下文采集器。
 * 负责从当前编辑器提取 FIM（Fill-in-the-Middle）所需的双层上下文：
 * 1. 核心层：纯文本 prefix + suffix，受 TokenBudgetManager 预算约束
 * 2. PSI 增强层：通过 [selectPsiStrategy] 选择语言策略提取结构化信息
 */
class CompletionContextCollector(
    private val budgetManager: TokenBudgetManager
) {
    fun collect(editor: Editor, project: Project): CompletionContext {
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = getLanguageFromExtension(virtualFile?.extension ?: "")

        // 核心层：纯文本 prefix + suffix
        val fullText = document.charsSequence.toString()
        val prefixRaw = fullText.substring(0, caretOffset.coerceAtMost(fullText.length))
        val suffixRaw = if (caretOffset < fullText.length) fullText.substring(caretOffset) else ""

        val prefix = prefixRaw.takeLast(budgetManager.maxPrefixChars)
        val suffix = suffixRaw.take(budgetManager.maxSuffixChars)

        // PSI 增强层
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val strategy = selectPsiStrategy(language)
        val smartContext = if (psiFile != null) {
            strategy.collectContext(editor, project, psiFile)?.take(budgetManager.maxSmartContextChars)
        } else null

        return CompletionContext(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileName = fileName,
            smartContext = smartContext
        )
    }

    private fun getLanguageFromExtension(ext: String): String = when (ext.lowercase()) {
        "php" -> "php"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "html", "htm" -> "html"
        "css" -> "css"
        "scss", "less" -> "css"
        "vue", "svelte" -> ext.lowercase()
        "py" -> "python"
        "go" -> "go"
        "rb" -> "ruby"
        "java" -> "java"
        "kt", "kts" -> "kotlin"
        else -> ext.lowercase().ifBlank { "text" }
    }
}
