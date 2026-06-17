package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File

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

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val relativePath = params["path"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("path 不能为空")
        val content = params["content"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("content 不能为空")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")

        // 安全检查：使用 PathUtils 统一检查，防止路径穿越（含 separator 边界）
        if (!com.aiassistant.shared.PathUtils.isInsideProject(relativePath, basePath)) {
            return ToolResult.err("安全限制：不能写入项目目录之外的文件")
        }

        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)
        return try {
            val targetFile = file.canonicalFile
            targetFile.parentFile?.mkdirs()
            // 原子写入：先写临时文件，成功后再 rename，避免写入中断导致原文件损坏
            val tmpFile = File(targetFile.path + ".tmp")
            tmpFile.writeText(content, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmpFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 跨文件系统降级：先 copy 再 delete
                java.nio.file.Files.move(
                    tmpFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                tmpFile.delete()
                return ToolResult.err("写入失败: 无法完成文件移动")
            }
            val lineCount = content.lines().size
            ToolResult.ok("文件已写入: $relativePath ($lineCount 行, ${content.length} 字符)")
        } catch (e: Exception) {
            ToolResult.err("写入失败: ${e.message}")
        }
    }
}
