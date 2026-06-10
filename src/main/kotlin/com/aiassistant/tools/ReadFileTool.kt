package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 读取文件内容的工具
 */
class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "读取指定文件的完整内容。修改文件前必须先读取。"
    override val parameters = listOf(
        ToolParameter("path", "string", "文件路径，相对于项目根目录", required = true),
        ToolParameter("offset", "integer", "起始行号（可选，从0开始）"),
        ToolParameter("limit", "integer", "读取行数（可选，默认读取全部）")
    )

    override fun execute(params: Map<String, String>, project: Project): ToolResult {
        val relativePath = params["path"] ?: return ToolResult.err("缺少 path 参数")
        val basePath = project.basePath ?: return ToolResult.err("项目路径不可用")
        val file = File(basePath, relativePath)

        if (!file.exists()) return ToolResult.err("文件不存在: $relativePath")
        if (!file.isFile) return ToolResult.err("不是文件: $relativePath")
        if (file.length() > 1_000_000) return ToolResult.err("文件过大 (${file.length() / 1024}KB)，请用 offset/limit 分段读取")

        val offset = params["offset"]?.toIntOrNull() ?: 0
        val limit = params["limit"]?.toIntOrNull()

        return try {
            val lines = file.readLines()
            val selected = if (limit != null) {
                lines.drop(offset).take(limit)
            } else {
                if (offset > 0) lines.drop(offset) else lines
            }
            val maxChars = 15000
            val numbered = selected.mapIndexed { i, line ->
                "${offset + i + 1}\t$line"
            }.joinToString("\n")
            val result = numbered.ifBlank { "(空文件)" }
            if (result.length > maxChars) {
                ToolResult.ok(result.take(maxChars) + "\n... (输出已截断，共 ${result.length} 字符，${selected.size} 行)")
            } else {
                ToolResult.ok(result)
            }
        } catch (e: Exception) {
            ToolResult.err("读取失败: ${e.message}")
        }
    }
}
