package com.aiassistant.agent

import org.yaml.snakeyaml.Yaml
import java.io.File

// 加载自定义 Agent 定义，兼容 Claude Code 格式（.claude/agents/ 目录下 .md 文件）。
// 使用 SnakeYAML 解析 frontmatter，完整支持 YAML 语法。
object AgentLoader {

    // YAML frontmatter 分隔符
    private val FRONTMATTER = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", setOf(RegexOption.DOT_MATCHES_ALL))

    data class CustomAgentDef(
        val name: String,
        val description: String,
        val tools: Set<String>?,
        val disallowedTools: Set<String>,
        val model: String?,
        val systemPrompt: String
    )

    // 加载所有自定义 Agent 定义。
    // 项目级 .claude/agents/ + 用户级 ~/.claude/agents/。
    // 用户级定义覆盖同名的项目级定义。
    fun loadAll(projectBasePath: String?): Map<String, CustomAgentDef> {
        val result = mutableMapOf<String, CustomAgentDef>()

        if (projectBasePath != null) {
            loadFromDir(File(projectBasePath, ".claude/agents"), result)
        }
        val userDir = File(System.getProperty("user.home"), ".claude/agents")
        loadFromDir(userDir, result)

        return result
    }

    private fun loadFromDir(dir: File, result: MutableMap<String, CustomAgentDef>) {
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.extension == "md" }?.forEach { file ->
            try {
                val def = parse(file)
                if (def != null) result[def.name] = def
            } catch (_: Exception) {
                // 解析失败的跳过
            }
        }
    }

    // 使用 SnakeYAML 解析 frontmatter
    private fun parse(file: File): CustomAgentDef? {
        val content = file.readText()
        val match = FRONTMATTER.find(content) ?: return null

        val frontmatter = match.groupValues[1]
        val body = match.groupValues[2].trim()

        val yaml = Yaml()
        @Suppress("UNCHECKED_CAST")
        val fields = yaml.load(frontmatter) as? Map<String, Any> ?: return null

        val name = fields["name"]?.toString() ?: file.nameWithoutExtension
        val description = fields["description"]?.toString() ?: name
        val tools = parseStringSet(fields["tools"])
        val disallowedTools = parseStringSet(fields["disallowedTools"]) ?: emptySet()
        val model = fields["model"]?.toString()?.takeIf { it.isNotBlank() }

        return CustomAgentDef(
            name = name,
            description = description,
            tools = tools,
            disallowedTools = disallowedTools,
            model = model,
            systemPrompt = body
        )
    }

    // 解析 tools/disallowedTools：支持逗号分隔字符串和 YAML 数组
    private fun parseStringSet(value: Any?): Set<String>? {
        return when (value) {
            is String -> value.split(",", "，").map { it.trim() }
                .filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() }
            is List<*> -> value.mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotBlank() }.toSet().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    // 将 CustomAgentDef 转换为 AgentType
    fun toAgentType(def: CustomAgentDef): AgentType = AgentType(
        name = def.name,
        description = def.description,
        systemPrompt = def.systemPrompt,
        allowedTools = def.tools,
        deniedTools = def.disallowedTools,
        autoApprove = true,
        defaultModel = def.model,
        maxLoops = AgentLoop.MAX_LOOPS,
        maxFailures = AgentLoop.MAX_FAILURES,
        timeoutMinutes = 5
    )
}
