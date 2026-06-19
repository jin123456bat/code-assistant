package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.aiassistant.agent.memory.MemoryRelevance
import com.intellij.openapi.project.Project

class MemoryReadTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_read"
    override val description = "读取记忆。可按 name 精确读取或按 query 关键词搜索相关记忆。"
    override val parameters = listOf(
        ToolParameter("query", "string", "搜索关键词（匹配 description 和正文）"),
        ToolParameter("name", "string", "按精确文件名读取（如 my-preference）"),
        ToolParameter("type", "string", "按类型过滤：user/feedback/project/reference",
            enum = listOf("user", "feedback", "project", "reference")),
        ToolParameter("scope", "string", "搜索范围：user/project/both，默认 both",
            enum = listOf("user", "project", "both"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val exactName = params["name"]?.trim()?.takeIf { it.isNotBlank() }
        val query = params["query"]?.trim()?.takeIf { it.isNotBlank() }

        if (exactName != null) {
            val entry = engine.read(exactName)
            return if (entry != null) {
                ToolResult.ok("""|## ${entry.name}
                    |**类型:** ${entry.type} | **描述:** ${entry.description}
                    |${entry.content}""".trimMargin())
            } else {
                ToolResult.err("未找到记忆: $exactName")
            }
        }

        if (query != null) {
            val index = engine.list()
            val relevance = MemoryRelevance()
            val matched = relevance.match(query, index)
            if (matched.isEmpty()) return ToolResult.ok("未找到与「$query」相关的记忆")
            val results = matched.mapNotNull { engine.read(it.name) }
            return ToolResult.ok(buildString {
                appendLine("搜索「$query」找到 ${results.size} 条相关记忆：")
                results.forEachIndexed { i, entry ->
                    appendLine("\n### ${i + 1}. ${entry.name} (${entry.type})")
                    appendLine("${entry.description}\n")
                    appendLine(entry.content)
                }
            })
        }

        val index = engine.list()
        if (index.isEmpty()) return ToolResult.ok("暂无记忆")
        return ToolResult.ok(buildString {
            appendLine("全部记忆（共 ${index.size} 条）：\n")
            index.forEach { entry ->
                appendLine("- **${entry.name}** (${entry.scope}): ${entry.description}")
            }
        })
    }
}
