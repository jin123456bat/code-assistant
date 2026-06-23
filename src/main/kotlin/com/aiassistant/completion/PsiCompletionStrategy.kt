package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag

/**
 * PSI 补全策略接口。各语言实现负责从当前编辑器提取增强上下文。
 * 返回 null 表示该策略无额外上下文可用。
 */
interface PsiCompletionStrategy {
    fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String?
}

// ---- Fallback: 纯文本 ----

class FallbackStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? = null
}

// ---- PHP Strategy ----

class PhpPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val document = editor.document
        val offset = editor.caretModel.offset
        val sb = StringBuilder()

        val element = psiFile.findElementAt(offset) ?: return null
        val containingFunction = try {
            @Suppress("UNCHECKED_CAST")
            val funcClass = Class.forName("com.jetbrains.php.lang.psi.elements.Function") as Class<com.intellij.psi.PsiElement>
            com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, funcClass)
        } catch (_: Exception) { null }  // PHP 插件未安装时降级
        if (containingFunction != null) {
            sb.appendLine("// ${containingFunction.text.take(500)}\n")
        } else {
            // 只取文件头部（namespace + use 声明通常在文件前 2000 字符内）
            val headerText = document.immutableCharSequence.take(2000).toString()
            val headerEnd = getPhpHeaderEnd(headerText)
            if (headerEnd > 0) {
                sb.appendLine(headerText.substring(0, headerEnd.coerceAtMost(1500)))
            }
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val useClass = Class.forName("com.jetbrains.php.lang.psi.elements.PhpUse") as Class<com.intellij.psi.PsiElement>
            val useStatements = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(psiFile, useClass)
            for (useStmt in useStatements.take(10)) {
                val fqn = useStmt.javaClass.simpleName // fallback
                sb.appendLine("// use $fqn")
            }
        } catch (_: Exception) {
            // PHP PSI 类不可用时跳过
        }

        return sb.takeIf { it.isNotBlank() }?.toString()
    }

    private fun getPhpHeaderEnd(text: String): Int {
        var pos = text.indexOf("<?php")
        if (pos < 0) pos = 0
        var current = pos
        for (line in text.substring(pos).lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("namespace ") || trimmed.startsWith("use ") ||
                trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            ) {
                current += line.length + 1  // +1 for \n
            } else {
                break
            }
        }
        return current.coerceAtMost(text.length)
    }
}

// ---- JS Strategy ----

class JsPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val document = editor.document
        val offset = editor.caretModel.offset
        val sb = StringBuilder()

        try {
            val element = psiFile.findElementAt(offset) ?: return null
            @Suppress("UNCHECKED_CAST")
            val jsFunctionClass = Class.forName("com.intellij.lang.javascript.psi.JSFunction") as Class<com.intellij.psi.PsiElement>
            val containingFunction = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, jsFunctionClass)
            if (containingFunction != null) {
                sb.appendLine("// ${containingFunction.text.take(500)}\n")
            }
            val text = document.charsSequence.toString()
            for (line in text.lines().take(50)) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ") ||
                    (trimmed.startsWith("const ") && trimmed.contains("require("))
                ) {
                    sb.appendLine(trimmed)
                }
            }
        } catch (_: Exception) {
            // JS PSI 不可用时降级
        }

        return sb.takeIf { it.isNotBlank() }?.toString()
    }
}

// ---- HTML Strategy ----

class HtmlPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        val parentTag = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            element, XmlTag::class.java
        )
        if (parentTag != null) {
            return "<!-- parent: <${parentTag.name}> -->\n${parentTag.text.take(500)}"
        }
        return null
    }
}

// ---- CSS Strategy ----

class CssPsiStrategy : PsiCompletionStrategy {
    override fun collectContext(editor: Editor, project: Project, psiFile: PsiFile): String? {
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        try {
            @Suppress("UNCHECKED_CAST")
            val cssBlockClass = Class.forName("com.intellij.psi.css.CssBlock") as Class<com.intellij.psi.PsiElement>
            val parentBlock = com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, cssBlockClass)
            if (parentBlock != null) {
                return "/* parent block */ {\n${parentBlock.text.take(500)}\n}"
            }
        } catch (_: Exception) {
            // CSS PSI 不可用时降级
        }
        return null
    }
}

// ---- 策略单例 ----

private val phpStrategy = PhpPsiStrategy()
private val jsStrategy = JsPsiStrategy()
private val htmlStrategy = HtmlPsiStrategy()
private val cssStrategy = CssPsiStrategy()
private val fallbackStrategy = FallbackStrategy()

// ---- 策略选择函数 ----

fun selectPsiStrategy(language: String): PsiCompletionStrategy = when (language.lowercase()) {
    "php" -> phpStrategy
    "javascript", "typescript" -> jsStrategy
    "html", "xml" -> htmlStrategy
    "css", "scss", "less" -> cssStrategy
    else -> fallbackStrategy
}
