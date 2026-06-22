package com.aiassistant.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Code Assistant 项目级启动入口。
 * 所有初始化（ToolWindow 创建、AgentLoop、MCP、Skill 监听）均在索引完成后执行，
 * 避免插件文件 I/O 与 IDE 索引抢占 fd 资源导致 "Too many open files"。
 */
class OpenAiToolWindowOnStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Code Assistant") ?: return
        // 全部延迟到索引完成后：toolWindow.show 触发 ChatToolWindowFactory → init → AgentLoop → MCP → Skills
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (toolWindow.isAvailable) {
                    toolWindow.show(null)
                }
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
