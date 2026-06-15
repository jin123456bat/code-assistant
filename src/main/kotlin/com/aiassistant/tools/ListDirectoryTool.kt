package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.shared.PathUtils
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 列出目录结构的工具
 */
class ListDirectoryTool : AgentTool {
    override val name = "list_directory"
    override val description = "列出项目目录结构，可指定子目录和递归深度"
    override val parameters = listOf(
        ToolParameter("path", "string", "目录路径，相对于项目根目录（可选，默认项目根目录）"),
        ToolParameter("max_depth", "integer", "最大递归深度，默认 3")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val relativePath = params["path"] ?: ""
        val maxDepth = params["max_depth"]?.toIntOrNull() ?: 3
        // 支持绝对路径（LLM 可能传完整路径）
        val targetDir = when {
            relativePath.isBlank() -> File(basePath)
            File(relativePath).isAbsolute -> File(relativePath)
            else -> File(basePath, relativePath)
        }

        // 路径穿越防护：确保目标目录在项目目录内
        if (!PathUtils.isInsideProject(targetDir.absolutePath, basePath)) {
            return ToolResult.err("路径穿越检测：拒绝访问项目目录外的路径 ${targetDir.absolutePath}")
        }

        if (!targetDir.exists()) return ToolResult.err("目录不存在: ${targetDir.absolutePath}")
        if (!targetDir.isDirectory) return ToolResult.err("不是目录: $relativePath")

        // 忽略的目录
        val ignoreDirs = setOf(".git", ".idea", ".gradle", "build", "node_modules", "__pycache__",
            ".venv", "vendor", ".code-assistant", "target", "out", ".DS_Store")

        return try {
            val result = buildString {
                append("$relativePath/\n")
                appendTree(targetDir, "", 1, maxDepth, ignoreDirs)
            }
            ToolResult.ok(if (result.length > 10000) result.take(10000) + "\n… (已截断)" else result)
        } catch (e: Exception) {
            ToolResult.err("列出目录失败: ${e.message}")
        }
    }

    private fun StringBuilder.appendTree(
        dir: File, prefix: String, depth: Int, maxDepth: Int, ignoreDirs: Set<String>
    ) {
        if (depth > maxDepth) return
        val entries = dir.listFiles()?.sortedBy { it.name } ?: return
        val visible = entries.filter { it.name !in ignoreDirs && !it.isHidden }
        for ((index, file) in visible.withIndex()) {
            val isLast = index == visible.size - 1
            val connector = if (isLast) "└── " else "├── "
            append("$prefix$connector${file.name}")
            if (file.isDirectory) {
                append("/\n")
                val nextPrefix = prefix + if (isLast) "    " else "│   "
                appendTree(file, nextPrefix, depth + 1, maxDepth, ignoreDirs)
            } else {
                append("\n")
            }
        }
    }
}
