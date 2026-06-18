package com.aiassistant.completion

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.logs.InlineCompletionUsageTracker.ShownEvents.FinishType
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 切换到上一个补全候选。默认快捷键：↑
 *
 * 当前 IntelliJ Platform 2023.3 的 InlineCompletion API 不支持多候选导航
 * （无 nextElement/previousElement API）。此 Action 在有补全会话时取消补全，
 * 无会话时恢复为光标上移。
 */
class PrevCandidateAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val session = InlineCompletionSession.getOrNull(editor)
        if (session != null) {
            // 有补全会话时，取消补全（模拟用户按 ESC）
            val context = session.context
            if (!context.isDisposed) {
                InlineCompletion.getHandlerOrNull(editor)?.hide(context, FinishType.ESCAPE_PRESSED)
            }
        } else {
            // 无补全会话时，恢复为普通光标移动
            editor.caretModel.moveCaretRelatively(0, -1, false, false, false)
        }
    }
}
