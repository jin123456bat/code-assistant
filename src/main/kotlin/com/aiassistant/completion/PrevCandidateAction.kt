package com.aiassistant.completion

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * 切换到上一个补全候选。
 *
 * 通过 InlineCompletionSession.usePrevVariant() 切换到上一个候选。
 * 快捷键由 [FimCandidateShortcutBinding] 绑定到当前 FIM 会话，不注册为全局快捷键。
 */
class PrevCandidateAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = findNavigableSession(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        findNavigableSession(e)?.usePrevVariant()
    }
}
