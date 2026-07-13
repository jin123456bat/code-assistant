package com.aiassistant.completion

import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 切换到下一个补全候选。
 *
 * 通过 InlineCompletionSession.useNextVariant() 切换到下一个候选。
 * 快捷键由 [FimCandidateShortcutBinding] 绑定到当前 FIM 会话，不注册为全局快捷键。
 */
class NextCandidateAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = findNavigableSession(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        findNavigableSession(e)?.useNextVariant()
    }
}

/**
 * 只在当前编辑器确实存在多个 FIM 候选时返回会话。
 *
 * 单候选或无会话时必须禁用动作，让 IntelliJ 原生光标移动继续处理方向键。
 */
internal fun findNavigableSession(e: AnActionEvent): InlineCompletionSession? {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val session = InlineCompletionSession.getOrNull(editor) ?: return null
    if (session.provider.id.id != AI_ASSISTANT_PROVIDER_ID) return null
    if (!session.isActive()) return null
    return session.takeIf { session.capture()?.variantsNumber?.let { it > 1 } == true }
}

/**
 * 将方向键动作绑定到当前 AI Assistant FIM 会话。
 *
 * [InlineCompletionSession] 本身是 Disposable，因此会话结束、插件卸载或补全失效时，
 * IntelliJ 会自动从编辑器组件注销动作，不需要长期监听全部编辑器。
 */
internal object FimCandidateShortcutBinding {
    private val boundSessionKey =
        Key.create<InlineCompletionSession>("ai-assistant.fim-candidate-shortcut-session")

    @RequiresEdt
    fun ensureRegistered(editor: Editor) {
        val session = InlineCompletionSession.getOrNull(editor) ?: return
        if (session.provider.id.id != AI_ASSISTANT_PROVIDER_ID) return
        if (editor.getUserData(boundSessionKey) === session) return

        registerActions(editor, session)
        editor.putUserData(boundSessionKey, session)
        Disposer.register(session) {
            if (editor.getUserData(boundSessionKey) === session) {
                editor.putUserData(boundSessionKey, null)
            }
        }
    }

    internal fun registerActions(editor: Editor, parentDisposable: Disposable) {
        NextCandidateAction().registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)),
            editor.contentComponent,
            parentDisposable,
        )
        PrevCandidateAction().registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)),
            editor.contentComponent,
            parentDisposable,
        )
    }
}

private const val AI_ASSISTANT_PROVIDER_ID = "ai-assistant"
