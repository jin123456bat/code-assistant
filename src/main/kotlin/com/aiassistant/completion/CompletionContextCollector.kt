package com.aiassistant.completion

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.io.File

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
 * 1. 核心层：纯文本 prefix + suffix，受 CharBudgetManager 预算约束
 * 2. PSI 增强层：通过 [selectPsiStrategy] 选择语言策略提取结构化信息
 */
class CompletionContextCollector {
    companion object {
        private const val MAX_CHARS = CharBudgetManager.MAX_CHARS
    }

    fun collect(editor: Editor, project: Project): CompletionContext {
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"
        val language = getLanguageFromExtension(virtualFile?.extension ?: "")

        val text = document.immutableCharSequence

        // 1. prefix：光标前全部内容
        var prefix = text.subSequence(0, caretOffset).toString()
        // 2. suffix：光标后全部内容
        var suffix = text.subSequence(caretOffset, text.length).toString()

        // 3. 如果不够 16K，加同目录同扩展名文件（最多 1 个）
        var smartContext: String? = null
        if (prefix.length + suffix.length < MAX_CHARS && virtualFile != null) {
            smartContext = findSiblingFile(virtualFile, language)?.let { siblingPath ->
                try {
                    File(siblingPath).readText().take(
                        MAX_CHARS - prefix.length - suffix.length
                    )
                } catch (_: Exception) { null }
            }
        }

        // 4. 如果超过 16K，截断（prefix 占 2/3，suffix 占 1/3）
        val total = prefix.length + suffix.length + (smartContext?.length ?: 0)
        if (total > MAX_CHARS) {
            val prefixBudget = (MAX_CHARS * CharBudgetManager.PREFIX_RATIO).toInt()
            prefix = prefix.takeLast(prefixBudget)
            val remaining = MAX_CHARS - prefix.length
            suffix = suffix.take(remaining.coerceAtLeast(0))
            smartContext = null
        }

        // 5. PSI 增强层
        val psiFile = ReadAction.compute<PsiFile?, Throwable> {
            PsiDocumentManager.getInstance(project).getPsiFile(document)
        }
        val psiContext = if (psiFile != null) {
            ReadAction.compute<String?, Throwable> {
                selectPsiStrategy(language).collectContext(editor, project, psiFile)
            }
        } else null

        return CompletionContext(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileName = fileName,
            smartContext = smartContext ?: psiContext
        )
    }

    private fun findSiblingFile(
        currentFile: com.intellij.openapi.vfs.VirtualFile,
        language: String
    ): String? {
        val parent = currentFile.parent ?: return null
        val currentName = currentFile.name
        val ext = currentFile.extension ?: return null
        val sibling = parent.children.firstOrNull {
            it.extension == ext && it.name != currentName && !it.isDirectory
        } ?: return null
        return sibling.path
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
