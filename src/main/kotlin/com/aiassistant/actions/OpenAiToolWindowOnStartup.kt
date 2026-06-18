package com.aiassistant.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 项目完全加载后显示 Code Assistant 工具窗口。
 * 使用 StartupActivity（postStartupActivity 扩展点）确保在 COMPONENTS_LOADED 之后运行。
 */
class OpenAiToolWindowOnStartup : StartupActivity {

    override fun runActivity(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Assistant") ?: return
        if (toolWindow.isAvailable) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && toolWindow.isAvailable) {
                    toolWindow.show(null)
                }
            }
        }
        // 自动追加 .code-assistant 到 .gitignore（幂等）
        ensureGitignoreHasCodeAssistant(project)
    }

    private fun ensureGitignoreHasCodeAssistant(project: Project) {
        val basePath = project.basePath ?: return
        val gitignoreFile = java.io.File(basePath, ".gitignore")
        if (!gitignoreFile.exists()) return
        try {
            val existing = gitignoreFile.readText()
            if (existing.contains(".code-assistant")) return
            val toAppend = if (existing.endsWith("\n")) ".code-assistant/\n" else "\n.code-assistant/\n"
            gitignoreFile.appendText(toAppend)
        } catch (_: Exception) {}
    }
}
