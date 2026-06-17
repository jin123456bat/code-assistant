package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.shared.PathUtils
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 读取文件内容的工具。
 *
 * 安全策略：
 * - 项目目录内文件：直接读取
 * - 项目目录外文件：AgentLoop 层触发审批卡，用户确认后才执行；拒绝则返回"文件不存在"
 * - 此处保留 canonical path 校验作为纵深防御
 */
class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "读取指定文件的完整内容。修改文件前必须先读取。项目目录外的文件需要用户确认。"
    override val parameters = listOf(
        ToolParameter("path", "string", "文件路径，相对于项目根目录", required = true),
        ToolParameter("offset", "integer", "起始行号（可选，从0开始）"),
        ToolParameter("limit", "integer", "读取行数（可选，默认读取全部）")
    )

    override fun execute(params: Map<String, String>, project: Project, onProgress: ((String) -> Unit)?): ToolResult {
        val path = params["path"]?.takeIf { it.isNotBlank() } ?: return ToolResult.err("path 不能为空")

        // MCP resource:// URI 路由（对齐 Claude Code：read_file 支持 resource:// 协议）
        if (path.startsWith("resource://")) {
            val mcpManager = com.aiassistant.mcp.McpManager.getInstance(project.basePath)
                ?: return ToolResult.err("MCP 管理器未初始化")
            val content = mcpManager.readResource(path)
                ?: return ToolResult.err("MCP 资源不存在或读取失败: $path")
            return ToolResult.ok(content)
        }

        val basePath = params["_worktree"] ?: project.basePath ?: return ToolResult.err("项目路径不可用")
        val file = if (File(path).isAbsolute) File(path) else File(basePath, path)

        if (!file.exists()) return ToolResult.err("文件不存在: ${file.absolutePath}")
        if (!file.isFile) return ToolResult.err("不是文件: $path")

        // 纵深防御：canonical path 前缀校验（AgentLoop 层已做一次，此处为二次保险）
        // 设计决策：安全拦截时统一返回"文件不存在"，不暴露实际路径也不透露项目目录位置
        if (!PathUtils.isInsideProject(path, basePath)) {
            return ToolResult.err("文件不存在: ${file.absolutePath}")
        }

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
