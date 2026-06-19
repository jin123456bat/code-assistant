package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.intellij.openapi.project.Project

class MemoryListTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_list"
    override val description = "列出所有已存储的记忆（索引列表）。"
    override val parameters = listOf(
        ToolParameter("scope", "string", "过滤范围：user/project/both，默认 both",
            enum = listOf("user", "project", "both"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val index = engine.list()
        if (index.isEmpty()) return ToolResult.ok("暂无记忆")

        val scopeFilter = params["scope"]
        val filtered = when (scopeFilter) {
            "user" -> index.filter { it.scope == "user" }
            "project" -> index.filter { it.scope == "project" }
            else -> index
        }

        return ToolResult.ok(buildString {
            appendLine("记忆列表（共 ${filtered.size} 条）：\n")
            filtered.forEachIndexed { i, entry ->
                val typeTag = engine.read(entry.name)?.type ?: "unknown"
                appendLine("${i + 1}. **${entry.name}** (${entry.scope}/${typeTag})")
                appendLine("   ${entry.description}")
            }
        })
    }
}
