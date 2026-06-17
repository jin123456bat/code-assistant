package com.aiassistant.completion

import com.intellij.openapi.keymap.KeymapManager
import javax.swing.KeyStroke

/**
 * 检测快捷键是否与 IDE 已有快捷键冲突。
 */
object ShortcutConflictDetector {

    data class ConflictResult(
        val isConflict: Boolean,
        val conflictActionName: String? = null
    )

    /**
     * 检测指定 KeyStroke 是否与已注册的快捷键冲突。
     * @return ConflictResult — isConflict=true 时 conflictActionName 为冲突的 Action 名称
     */
    fun checkConflict(keyStroke: KeyStroke): ConflictResult {
        val activeKeymap = KeymapManager.getInstance().activeKeymap
        val actionIds = activeKeymap.getActionIds(keyStroke)
        if (actionIds.isNullOrEmpty()) return ConflictResult(false)

        val firstActionId = actionIds[0]
        val actionName = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction(firstActionId)?.templatePresentation?.text ?: firstActionId

        return ConflictResult(true, actionName)
    }

    /** 将设置中的快捷键字符串转为 KeyStroke */
    fun parseShortcutString(shortcut: String): KeyStroke? {
        return try {
            KeyStroke.getKeyStroke(shortcut)
        } catch (e: Exception) {
            null
        }
    }
}
