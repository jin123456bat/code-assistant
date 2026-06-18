package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 精确字符串替换工具（对齐 Claude Code Edit）。
 *
 * 与 write_file 的区别：只替换文件中精确匹配的一段文本，而非覆盖整个文件。
 * 要求 old_string 在文件中恰好出现一次（独一无二），否则报错并给出上下文。
 */
class EditTool : AgentTool {
    override val name = "edit_file"
    override val description = "精确替换文件中的一段文本。old_string 必须在文件中唯一匹配，否则会报错。用于局部修改，避免覆盖整个文件。"
    override val parameters = listOf(
        ToolParameter("file_path", "string", "要编辑的文件路径，相对于项目根目录", required = true),
        ToolParameter("old_string", "string", "要替换的原始文本（必须与文件中内容精确匹配，且唯一）", required = true),
        ToolParameter("new_string", "string", "替换后的新文本", required = true)
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val relativePath = params["file_path"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("file_path 不能为空")
        val oldString = params["old_string"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("old_string 不能为空")
        val newString = params["new_string"] ?: ""  // 允许替换为空字符串（即删除）

        val basePath = params["_worktree"] ?: project.basePath
            ?: return ToolResult.err("项目路径不可用")

        // 安全检查
        if (!com.aiassistant.shared.PathUtils.isInsideProject(relativePath, basePath)) {
            return ToolResult.err("安全限制：不能编辑项目目录之外的文件")
        }

        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(basePath, relativePath)

        return try {
            val targetFile = file.canonicalFile
            if (!targetFile.isFile) {
                return ToolResult.err("文件不存在: $relativePath")
            }
            // 检测二进制文件（含 NULL 字节），拒绝编辑防止破坏
            val head = targetFile.inputStream().use { it.readNBytes(4096) }
            if (head.any { it.toInt() == 0 }) {
                return ToolResult.err("无法编辑二进制文件: $relativePath")
            }

            val originalContent = targetFile.readText(Charsets.UTF_8)

            // 查找 old_string 出现次数
            val occurrences = findAllOccurrences(originalContent, oldString)
            if (occurrences.isEmpty()) {
                // 尝试模糊匹配：检查是否因缩进/换行差异导致不匹配
                val strippedOld = oldString.trimEnd()
                val strippedOccurrences = findAllOccurrences(originalContent, strippedOld)
                if (strippedOccurrences.isNotEmpty()) {
                    val hint = buildString {
                        append("old_string 未找到（存在末尾空白差异）。请移除 old_string 末尾多余的空格/换行后重试。")
                        if (strippedOccurrences.size == 1) {
                            append("\n\n提示：去掉末尾空白后可以匹配到以下位置的第 ${strippedOccurrences[0].line} 行")
                        }
                    }
                    return ToolResult.err(hint)
                }
                return ToolResult.err("old_string 在文件中未找到。请确认文本内容精确匹配（包括缩进、换行）。")
            }
            if (occurrences.size > 1) {
                val contextLines = occurrences.take(5).joinToString("\n") { (line, _) ->
                    val match = originalContent.lines().getOrElse(line - 1) { "" }
                    "  第 ${line} 行: ${match.trim().take(80)}"
                }
                val extra = if (occurrences.size > 5) "\n  ... 以及其他 ${occurrences.size - 5} 处" else ""
                return ToolResult.err(
                    "old_string 在文件中出现了 ${occurrences.size} 次，不唯一。请扩大匹配范围使 old_string 唯一（包含更多上下文）。\n\n匹配位置：\n$contextLines$extra"
                )
            }

            // 唯一匹配 → 替换
            val (line, _) = occurrences[0]
            val startIndex = originalContent.indexOf(oldString)
            val newContent = originalContent.substring(0, startIndex) + newString +
                    originalContent.substring(startIndex + oldString.length)

            // 原子写入（对齐 WriteFileTool）
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.path + ".tmp")
            tmpFile.writeText(newContent, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmpFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmpFile.toPath(), targetFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                tmpFile.delete()
                return ToolResult.err("写入失败: 无法完成文件移动")
            }

            // 构造变更上下文（±3 行，对齐 Claude Code Edit 的输出格式）
            val allLines = originalContent.lines()
            val ctxStart = (line - 4).coerceAtLeast(0)
            val ctxEnd = (line - 1 + oldString.lines().size + 3).coerceAtMost(allLines.size)
            val contextLines = allLines.subList(ctxStart, ctxEnd)
                .mapIndexed { i, l -> "${ctxStart + i + 1}\t$l" }.joinToString("\n")
            val oldLines = oldString.lines().size
            val newLines = newString.lines().size
            val changeDesc = when {
                newString.isEmpty() -> "删除了第 $line 行的 ${oldLines} 行内容"
                oldString.isEmpty() -> "在第 $line 行插入了 ${newLines} 行内容"
                oldLines == 1 && newLines == 1 -> "第 $line 行已更新"
                else -> "第 $line 行: ${oldLines} 行 → ${newLines} 行"
            }
            // 输出 diff 标记供 UI 层渲染 diff 按钮
            val maxEmbed = 5000
            val truncatedOld = if (oldString.length > maxEmbed) oldString.take(maxEmbed) + "\n…" else oldString
            val truncatedNew = if (newString.length > maxEmbed) newString.take(maxEmbed) + "\n…" else newString
            ToolResult.ok("文件已编辑: $relativePath\n$changeDesc\n\n变更上下文（第 ${ctxStart + 1}-${ctxEnd} 行）:\n$contextLines\n\n[OLD_CONTENT]\n$truncatedOld\n[/OLD_CONTENT]\n\n[NEW_CONTENT]\n$truncatedNew\n[/NEW_CONTENT]")
        } catch (e: Exception) {
            ToolResult.err("编辑失败: ${e.message}")
        }
    }

    /** 查找所有出现位置（最多 6 处，用于不唯一检测和上下文展示） */
    private fun findAllOccurrences(content: String, target: String): List<Occurrence> {
        val results = mutableListOf<Occurrence>()
        var startIndex = 0
        while (results.size < 100) {  // 安全上限防止大文件退化
            val idx = content.indexOf(target, startIndex)
            if (idx < 0) break
            val line = content.substring(0, idx).count { it == '\n' } + 1
            results.add(Occurrence(line, 0))
            startIndex = idx + 1
        }
        return results
    }

    private data class Occurrence(val line: Int, val col: Int)
}
