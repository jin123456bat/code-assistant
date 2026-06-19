package com.aiassistant.tools

import com.aiassistant.agent.AgentTool
import com.aiassistant.agent.ToolParameter
import com.aiassistant.agent.ToolResult
import com.aiassistant.agent.memory.MemoryEngine
import com.aiassistant.agent.memory.MemoryEntry
import com.intellij.openapi.project.Project

class MemoryWriteTool(private val engine: MemoryEngine) : AgentTool {
    override val name = "memory_write"
    override val description = "写入一条记忆。跨会话持久化用户偏好、项目约定、决策记录等。存在同名记忆时覆盖更新。"
    override val parameters = listOf(
        ToolParameter("name", "string", "记忆文件名（kebab-case）", required = true),
        ToolParameter("description", "string", "一句话描述，用于检索", required = true),
        ToolParameter("content", "string", "记忆正文。必须包含 **Why:** 和 **How to apply:** 段落", required = true),
        ToolParameter("type", "string", "记忆类型：user/feedback/project/reference", required = true,
            enum = listOf("user", "feedback", "project", "reference")),
        ToolParameter("scope", "string", "作用域：user(全局跨项目)/project(当前项目)，默认 project",
            enum = listOf("user", "project"))
    )

    override fun execute(params: Map<String, String>, project: Project,
                         onProgress: ((String) -> Unit)?): ToolResult {
        val name = params["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("name 不能为空")
        val description = params["description"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("description 不能为空")
        val content = params["content"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.err("content 不能为空")
        val type = params["type"]?.takeIf { it in setOf("user", "feedback", "project", "reference") }
            ?: return ToolResult.err("type 必须是 user/feedback/project/reference 之一")
        val scope = params["scope"] ?: "project"

        if (!name.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$"))) {
            return ToolResult.err("name 必须符合 kebab-case 格式（小写字母、数字、连字符，如 my-preference）")
        }

        val entry = MemoryEntry(name, description, content, type, scope)
        return engine.write(entry).fold(
            onSuccess = { ToolResult.ok("记忆已保存: $name ($description)") },
            onFailure = { e -> ToolResult.err("写入记忆失败: ${e.message}") }
        )
    }
}
