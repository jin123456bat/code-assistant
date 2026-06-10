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
    }
}
