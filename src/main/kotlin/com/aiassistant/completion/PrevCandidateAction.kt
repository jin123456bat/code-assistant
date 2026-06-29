package com.aiassistant.completion

import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 切换到上一个补全候选。默认快捷键：↑
 *
 * 通过 InlineCompletionSession.usePrevVariant() 切换到上一个候选。
 * 无补全会话时恢复为普通光标上移。
 */
class PrevCandidateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session = InlineCompletionSession.getOrNull(editor)
        if (session != null) {
            // 有补全会话时，切换到上一个候选
            session.usePrevVariant()
        } else {
            // 无补全会话时，恢复为普通光标移动
            editor.caretModel.moveCaretRelatively(0, -1, false, false, false)
        }
    }
}
