package com.aiassistant.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiRecursiveElementVisitor

/**
 * PSI 增强上下文采集。支持 PHP/Kotlin/Java 等多语言。
 * PHP 使用专用的 PHP PSI 类（com.jetbrains.php 可选依赖），不可用时静默降级。
 * 其他语言使用通用 PsiClass/PsiMethod 接口采集 class/method 声明和 import 语句。
 */
object PsiCompletionStrategy {
    fun collectContext(editor: Editor, project: Project, psiFile: PsiFile, language: String): String? {
        return when {
            language.equals("php", ignoreCase = true) -> collectPhpContext(editor, psiFile)
            else -> collectGenericPsiContext(psiFile)
        }
    }

    // ---- PHP 专用上下文采集 ----

    private fun collectPhpContext(editor: Editor, psiFile: PsiFile): String? {
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

    // ---- 通用 PSI 上下文采集（Kotlin/Java 等非 PHP 语言） ----

    /**
     * 使用通用 IntelliJ PSI 接口（不依赖语言特定插件）遍历文件，
     * 提取 import 语句、class/interface 声明、method/function 声明。
     */
    private fun collectGenericPsiContext(psiFile: PsiFile): String? {
        val sb = StringBuilder()
        val imports = mutableListOf<String>()
        val classes = mutableListOf<String>()
        val functions = mutableListOf<String>()

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when {
                    // import 语句：识别以 "import " 开头的顶层声明
                    element.text.trimStart().startsWith("import ") -> {
                        imports.add(element.text.trim().take(200))
                    }
                    // class/interface/enum/object 声明
                    element is PsiClass -> {
                        val modifiers = mutableListOf<String>()
                        if (element.hasModifierProperty("abstract")) modifiers.add("abstract")
                        if (element.hasModifierProperty("open")) modifiers.add("open")
                        val kind = when {
                            element.isInterface -> "interface"
                            element.isEnum -> "enum"
                            else -> "class"
                        }
                        classes.add((modifiers + kind + (element.name ?: "")).joinToString(" "))
                        // 继续递归进入类内部以收集方法声明
                        super.visitElement(element)
                    }
                    // method/function 声明
                    element is PsiMethod -> {
                        val methodText = element.text.take(250).replace("\n", " ").trim()
                        functions.add(methodText)
                    }
                    else -> super.visitElement(element)
                }
            }
        })

        if (imports.isNotEmpty()) {
            imports.take(15).forEach { sb.appendLine("// $it") }
            sb.appendLine()
        }
        classes.take(5).forEach { sb.appendLine("// $it") }
        if (functions.isNotEmpty()) {
            sb.appendLine()
            functions.take(10).forEach { sb.appendLine("//   ${it.trim()}") }
        }

        return sb.takeIf { it.isNotBlank() }?.toString()
    }
}
