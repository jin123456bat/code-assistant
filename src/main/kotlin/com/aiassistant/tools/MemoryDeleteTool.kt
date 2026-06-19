package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.intellij.openapi.project.Project

class MemoryDeleteTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_delete"
    override val description = "删除一条记忆。"
    override val parameters = listOf(
        ToolParameter("name", "string", "要删除的记忆文件名", required = true)
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val name = params["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("name 不能为空")

        return engine.delete(name).fold(
            onSuccess = { ToolResult.ok("记忆已删除: $name") },
            onFailure = { e -> ToolResult.err("删除失败: ${e.message}") }
        )
    }
}
