package com.aiassistant.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager

/**
 * 右键菜单 Action 回调桥接——ChatToolWindow 注册 handler，右键菜单通过此桥接触发审查。
 */
object ReviewActionBridge {
    @Volatile var onReviewSelectedCode: ((String, String) -> Unit)? = null  // (filePath, selectedCode)
    @Volatile var onSecurityReviewFile: ((String) -> Unit)? = null           // (filePath)
}

class ReviewSelectedCodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val code = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: ""
        val handler = ReviewActionBridge.onReviewSelectedCode
        if (handler != null) {
            handler(file.path, code)
        } else {
            // 回退：发送到聊天窗口
            val msg = if (code.isNotBlank()) "请审查以下选中代码（${file.name}）：\n```\n${code.take(3000)}\n```"
            else "请审查文件 ${file.name}"
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, msg)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PROJECT) != null
    }
}

class SecurityReviewFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)?.virtualFile ?: return
        val handler = ReviewActionBridge.onSecurityReviewFile
        if (handler != null) {
            handler(file.path)
        } else {
            com.aiassistant.ChatToolWindow.sendMessageToChat(project, "请对 ${file.name} 进行安全审查，检查注入向量、密钥泄漏、权限缺陷、不安全 API 和依赖漏洞。")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.PSI_FILE) != null
    }
}
