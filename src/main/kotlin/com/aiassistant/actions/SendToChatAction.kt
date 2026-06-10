package com.aiassistant.actions

import com.aiassistant.ChatToolWindow
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager

class SendToChatAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabled = editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return
        val filePath = getRelativePath(project, editor) ?: "unknown"
        val language = getLanguage(project, editor) ?: ""

        val codeRef = buildCodeReference(filePath, language, selectedText)
        ChatToolWindow.insertText(project, codeRef)
    }

    private fun getRelativePath(project: Project, editor: Editor): String? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val basePath = project.basePath ?: return file.name
        val absPath = file.path
        return if (absPath.startsWith(basePath)) {
            absPath.removePrefix(basePath).removePrefix("/")
        } else {
            file.name
        }
    }

    private fun getLanguage(project: Project, editor: Editor): String? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        return psiFile?.language?.id?.lowercase()
    }

    private fun buildCodeReference(filePath: String, language: String, code: String): String {
        val langTag = language.ifBlank { "" }
        val normalizedCode = if (code.endsWith("\n")) code else "$code\n"
        return buildString {
            append("`$filePath`\n")
            append("\n")
            append("```$langTag\n")
            append(normalizedCode)
            append("```\n")
            append("\n")
        }
    }
}
