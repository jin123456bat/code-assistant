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
        // 允许空字符串（清空文件或创建空文件），但拒绝 null（参数缺失）
        val content = params["content"] ?: return ToolResult.err("content 不能为空")
        val basePath = params["_worktree"] ?: project.basePath ?: return ToolResult.err("项目路径不可用")

        // 安全检查：使用 PathUtils 统一检查，防止路径穿越（含 separator 边界）
        if (!com.aiassistant.shared.PathUtils.isInsideProject(relativePath, basePath)) {
            return ToolResult.err("安全限制：不能写入项目目录之外的文件")
        }

        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)
        return try {
            val targetFile = file.canonicalFile
            // 写之前记录文件是否已存在（写入后 exists 永远为 true，无法判断 isNew）
            val fileExistedBefore = targetFile.isFile
            // 写之前读取旧内容（用于 diff 对比，不存在则为空）
            val oldContent = if (fileExistedBefore) targetFile.readText(Charsets.UTF_8) else ""
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
            val isNew = !fileExistedBefore
            val header = if (isNew) "文件已创建: $relativePath ($lineCount 行, ${content.length} 字符)"
                        else "文件已写入: $relativePath ($lineCount 行, ${content.length} 字符)"
            // 嵌入旧/新内容标记，供 UI 层渲染 diff 按钮（新文件无需 diff）
            // 大文件截断：每部分最多 5000 字符，避免撑爆 LLM 上下文
            val maxEmbedChars = 5000
            val sb = StringBuilder(header)
            if (!isNew && oldContent.isNotEmpty()) {
                val truncatedOld = if (oldContent.length > maxEmbedChars) oldContent.take(maxEmbedChars) + "\n… (已截断)" else oldContent
                sb.append("\n\n[OLD_CONTENT]\n").append(truncatedOld).append("\n[/OLD_CONTENT]")
            }
            val truncatedNew = if (content.length > maxEmbedChars) content.take(maxEmbedChars) + "\n… (已截断)" else content
            sb.append("\n\n[NEW_CONTENT]\n").append(truncatedNew).append("\n[/NEW_CONTENT]")
            ToolResult.ok(sb.toString())
        } catch (e: Exception) {
            ToolResult.err("写入失败: ${e.message}")
        }
    }
}
