package com.aiassistant.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 项目完全加载后显示 Code Assistant 工具窗口。
 * 使用 ProjectActivity（替代已废弃的 StartupActivity/postStartupActivity）确保在 COMPONENTS_LOADED 之后运行。
 */
class OpenAiToolWindowOnStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Assistant") ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (toolWindow.isAvailable) {
                toolWindow.show(null)
            }
            // 延迟到索引完成后执行：写 .gitignore 会触发 VFS 刷新 → 索引扫描，
            // 此时若 Kotlin builtins 尚未就绪会报 "Virtual file for builtin is not found"
            DumbService.getInstance(project).runWhenSmart {
                ensureGitignoreHasCodeAssistant(project)
            }
        }
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
