package com.aiassistant.completion

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 切换到下一个补全候选。默认快捷键：↓
 *
 * 当前 IntelliJ Platform 2023.3 的 InlineCompletion API 不支持多候选导航
 * （无 nextElement/previousElement API）。此 Action 在有补全会话时执行插入，
 * 无会话时恢复为光标下移。
 */
class NextCandidateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session = InlineCompletionSession.getOrNull(editor)
        if (session != null) {
            // 有补全会话时，执行插入操作
            InlineCompletion.getHandlerOrNull(editor)?.insert()
        } else {
            // 无补全会话时，恢复为普通光标移动
            editor.caretModel.moveCaretRelatively(0, 1, false, false, false)
        }
    }
}
