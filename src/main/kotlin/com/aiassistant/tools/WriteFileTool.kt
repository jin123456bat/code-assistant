package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 创建或覆写文件的工具（需用户确认）
 */
class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "创建新文件或覆写已有文件。首次写入需用户确认。"
    override val parameters = listOf(
        ToolParameter("path", "string", "文件路径，相对于项目根目录", required = true),
        ToolParameter("content", "string", "要写入的文件内容", required = true)
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val relativePath = params["path"] ?: return ToolResult.err("缺少 path 参数")
        val content = params["content"] ?: return ToolResult.err("缺少 content 参数")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")

        // 安全检查：不允许写入系统目录
        val normalizedPath = File(basePath, relativePath).canonicalPath
        val normalizedBase = File(basePath).canonicalPath
        if (!normalizedPath.startsWith(normalizedBase)) {
            return ToolResult.err("安全限制：不能写入项目目录之外的文件")
        }

        // 用户确认
        val confirmed = AtomicReference<Boolean>(false)
        ApplicationManager.getApplication().invokeAndWait {
            val preview = content.take(500).let { if (content.length > 500) "$it..." else it }
            val msg = """Agent 即将写入文件:

文件: $relativePath
行数: ${content.lines().size} 行
大小: ${content.length} 字符

内容预览:
$preview

是否允许此写入操作？"""
            val choice = Messages.showYesNoDialog(
                project, msg, "Code Assistant - 文件写入确认", Messages.getQuestionIcon()
            )
            confirmed.set(choice == Messages.YES)
        }
        if (!confirmed.get()) {
            return ToolResult.err("用户拒绝写入文件: $relativePath")
        }

        return try {
            val file = File(normalizedPath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            val lineCount = content.lines().size
            ToolResult.ok("文件已写入: $relativePath ($lineCount 行, ${content.length} 字符)")
        } catch (e: Exception) {
            ToolResult.err("写入失败: ${e.message}")
        }
    }
}
