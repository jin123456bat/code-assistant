package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * PHP PSI 增强上下文采集。非 PHP 文件返回 null。
 * 通过反射加载 PHP PSI 类（com.jetbrains.php 可选依赖），不可用时静默降级。
 */
object PsiCompletionStrategy {
    fun collectContext(editor: Editor, project: Project, psiFile: PsiFile, language: String): String? {
        if (!language.equals("php", ignoreCase = true)) return null

        val document = editor.document
        val offset = editor.caretModel.offset
        val sb = StringBuilder()

        val element = psiFile.findElementAt(offset) ?: return null
        val containingFunction = try {
            @Suppress("UNCHECKED_CAST")
            val funcClass = Class.forName("com.jetbrains.php.lang.psi.elements.Function") as Class<com.intellij.psi.PsiElement>
            com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, funcClass)
        } catch (_: Exception) { null }

        if (containingFunction != null) {
            sb.appendLine("// ${containingFunction.text.take(500)}\n")
        } else {
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
                sb.appendLine("// use ${useStmt.text}")
            }
        } catch (_: Exception) { }

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
                current += line.length + 1
            } else {
                break
            }
        }
        return current.coerceAtMost(text.length)
    }
}
