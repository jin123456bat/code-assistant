package com.aiassistant.completion

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * 手动触发 AI 代码补全。快捷键：Mac Cmd+P / Win Alt+P。
 *
 * 通过 [InlineCompletionEvent.DirectCall] 触发补全，绕过自动补全的缓存和 debounce 逻辑，
 * 直接请求 FIM 模型获取新的补全建议。
 */
class ManualCompletionAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        handler.invoke(InlineCompletionEvent.DirectCall(editor, editor.caretModel.currentCaret))
    }
}
