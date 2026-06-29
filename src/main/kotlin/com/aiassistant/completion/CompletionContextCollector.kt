package com.aiassistant.completion

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * 补全上下文数据结构。
 * - prefix: 光标前的纯文本
 * - suffix: 光标后的纯文本
 * - language: 文件语言标识
 * - fileName: 文件名
 * - smartContext: 增强上下文（PSI 语义 + 兄弟文件风格，预算层合并）。PSI 在前，sibling 在后。
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
 * 2. 增强层：PSI 语义上下文（函数签名、类型信息）+ 兄弟文件 smartContext（编码风格）
 *    二者并行采集后在预算层合并，PSI 优先保留（体积小、信息密度高）。
 */
class CompletionContextCollector {
    companion object {
        private const val MAX_CHARS = CharBudgetManager.MAX_CHARS
    }

    fun collect(editor: Editor, project: Project): CompletionContext {
        // editor model 访问必须在 EDT 或 ReadAction 中执行
        val snapshot = ReadAction.compute<Snapshot, Throwable> {
            val doc = editor.document
            val offset = editor.caretModel.offset
            val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(doc)
            val name = vf?.name ?: "unknown"
            val lang = getLanguageFromExtension(vf?.extension ?: "")
            Snapshot(doc, offset, name, lang, vf)
        }
        val document = snapshot.document
        val caretOffset = snapshot.caretOffset
        val fileName = snapshot.fileName
        val language = snapshot.language
        val virtualFile = snapshot.virtualFile

        val text = document.immutableCharSequence

        // 1. prefix：光标前全部内容
        var prefix = text.subSequence(0, caretOffset).toString()
        // 2. suffix：光标后全部内容
        var suffix = text.subSequence(caretOffset, text.length).toString()

        // 3. PSI 语义上下文：体积小（函数签名 + use/import）、信息密度高，始终采集
        val psiFile = ReadAction.compute<PsiFile?, Throwable> {
            PsiDocumentManager.getInstance(project).getPsiFile(document)
        }
        val psiContext = if (psiFile != null) {
            ReadAction.compute<String?, Throwable> {
                PsiCompletionStrategy.collectContext(editor, project, psiFile, language)
            }
        } else null

        // 4. 兄弟文件增强：仅当上下文 < 8K 时触发，避免 I/O 拖慢补全延迟
        //    兄弟文件提供编码风格、项目模式，与 PSI 语义互补
        var smartContext: String? = null
        if (prefix.length + suffix.length < 8192 && virtualFile != null) {
            val currentImports = ContextEnhancer.extractImportLinesFromText(prefix, language)
            val siblingPaths = ContextEnhancer.findBestSiblingFiles(
                virtualFile,
                virtualFile.extension ?: "",
                currentImports,
                project
            )
            if (siblingPaths.isNotEmpty()) {
                // 预算分配：PSI 优先保留，兄弟文件填充剩余空间
                val psiLen = psiContext?.length ?: 0
                val budget = MAX_CHARS - prefix.length - suffix.length - psiLen
                if (budget > 0) {
                    smartContext = try {
                        siblingPaths.joinToString(separator = "\n") { path ->
                            java.io.File(path).readText()
                        }.take(budget)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        // 5. 合并增强上下文：PSI 在前（语义优先）+ smartContext 在后（风格参考）
        val mergedContext = listOfNotNull(psiContext, smartContext)
            .joinToString(separator = "\n").takeIf { it.isNotBlank() }

        // 6. 如果超过 16K，截断（prefix 占 2/3，suffix 占 1/3。增强上下文已在上一步预算控制中处理）
        val total = prefix.length + suffix.length + (mergedContext?.length ?: 0)
        if (total > MAX_CHARS) {
            val prefixBudget = (MAX_CHARS * CharBudgetManager.PREFIX_RATIO).toInt()
            prefix = prefix.takeLast(prefixBudget)
            val remaining = MAX_CHARS - prefix.length
            suffix = suffix.take(remaining.coerceAtLeast(0))
        }

        return CompletionContext(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileName = fileName,
            smartContext = mergedContext
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

    /** ReadAction 中采集的 editor snapshot，避免跨线程访问 editor model */
    private data class Snapshot(
        val document: Document,
        val caretOffset: Int,
        val fileName: String,
        val language: String,
        val virtualFile: VirtualFile?
    )
}
