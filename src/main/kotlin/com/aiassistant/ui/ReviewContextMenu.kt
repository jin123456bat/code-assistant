package com.aiassistant.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class ReviewSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return

        if (editor != null) {
            val selection = editor.selectionModel.selectedText
            if (!selection.isNullOrBlank()) {
                Messages.showInfoMessage(project, "选中 ${selection.length} 个字符，审查功能已启动", "代码审查")
                return
            }
        }
        Messages.showInfoMessage(project, "将对 ${file.name} 进行审查", "代码审查")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class SecurityReviewFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        Messages.showInfoMessage(project, "将对 ${file.name} 进行安全审查", "安全审查")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PSI_FILE) != null
    }
}
